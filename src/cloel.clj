(ns cloel
  (:require [clojure.edn :as edn]
            [clojure.string :as str])
  (:import [java.io BufferedReader InputStreamReader PrintWriter OutputStreamWriter]
           [java.net ServerSocket Socket InetSocketAddress]
           [java.nio.charset StandardCharsets])
  (:gen-class))

(def client-connection (atom nil))
(def call-results (atom {}))
(def call-id (atom 0))
(def running (atom true))

(defn send-to-client [message]
  "Send message to Emacs client with null-check and error recovery.
Handles potential Windows socket issues with additional error checking."
  (let [conn @client-connection]
    (if (and conn (:writer conn) (not (.isClosed (:socket conn))))
      (try
        (let [line (pr-str message)
              writer (:writer conn)]
          ;; Defensive check for newlines, ensure message does not contain unescaped newlines
          (when (or (str/includes? line "\n") (str/includes? line "\r"))
            (println "[SEND-WARN] Message contains newline:" (subs line 0 (min 50 (count line)))))
          ;; On Windows ensure use \n instead of \r\n
          (let [clean-line (str/replace line "\r\n" "\n")]
            (.println writer clean-line)
            (.flush writer))
          true)  ;; Return success
        (catch Exception e
          (println "[SEND-ERROR] Failed to send:" (.getMessage e))
          ;; Reset connection only on actual exception, not preemptively
          (when (or (instance? java.net.SocketException e)
                    (instance? java.io.IOException e))
            (println "[SEND] Resetting connection due to socket error")
            (reset! client-connection nil))
          false))
      (do
        (when (and conn (:socket conn) (.isClosed (:socket conn)))
          (println "[SEND-ERROR] Socket is closed, resetting connection")
          (reset! client-connection nil))
        (println "[SEND-ERROR] No active connection or writer is nil")
        false))))
	
(defn generate-call-id []
  (swap! call-id inc))

(defn ^:export elisp-call [method & args]
  "Synchronously call Emacs Lisp function.
Includes timeout handling and connection validation."
  (let [id (generate-call-id)
        promise (promise)]
    (swap! call-results assoc id promise)
    (if (send-to-client {:type :call-elisp-sync :id id :method method :args args})
      (let [result (deref promise 60000 :timeout)]
        (swap! call-results dissoc id)
        (if (= result :timeout)
          (throw (Exception. (str "Timeout waiting for Elisp response for id: " id)))
          result))
      (do
        (swap! call-results dissoc id)
        (throw (Exception. "Failed to send message to client, connection may be closed"))))))

(defn ^:export elisp-eval-sync [func & args]
  (elisp-call :get-func-result func args))

(defn ^:export elisp-eval-async [func & args]
  "Asynchronously evaluate Emacs Lisp function.
Catches and logs errors without throwing to prevent cascading failures."
  (try
    (send-to-client {:type :call-elisp-async :func func :args args})
    (catch Exception e
      (println "[ASYNC-ERROR] elisp-eval-async failed:" (.getMessage e))
      (.printStackTrace e))))

(defn ^:export elisp-show-message [& args]
  (let [message (apply str args)]
    (elisp-eval-async "message" message)))

(defn ^:export elisp-get-var [var-name]
  (elisp-call :get-var-result var-name))

;; Default handler (will be overridden by application)
(defn ^:dynamic handle-client-async-call [data]
  (future
    (try
      (let [{:keys [func args]} data]
        (println "Default async handler:" func args)
        (if-let [f (resolve (symbol func))]
          (apply f args)
          (println "Function not found:" func)))
      (catch Exception e
        (println "ERROR in default async handler:" (.getMessage e))
        (.printStackTrace e)))))

(defn ^:dynamic handle-client-sync-call [data]
  (future
    (try
      (let [{:keys [id func args]} data
            result (try
                     {:value (apply (resolve (symbol func)) args)}
                     (catch Exception e
                       {:error (.getMessage e)}))]
        (send-to-client {:type :clojure-sync-return
                         :id id
                         :result result}))
      (catch Exception e
        (println "ERROR in sync handler:" (.getMessage e))
        (.printStackTrace e)))))

(defn ^:dynamic handle-client-message [data]
  (println "Received unhandled message type:" (pr-str data)))

(defn ^:dynamic handle-client-connected [client-id]
  (println "Client connected:" client-id))

;; Key fix: robust type matching (accepts both keywords and strings)
(defn- match-msg-type? [actual-type expected-type]
  (or (= actual-type expected-type)
      (= (keyword actual-type) expected-type)
      (= actual-type (name expected-type))))

;; Robust line reading that handles Windows CRLF and buffering issues
(defn- read-line-robust [^BufferedReader reader]
  "Read a line from reader, handling Windows CRLF and potential encoding issues.
Returns nil on EOF or error."
  (try
    (let [line (.readLine reader)]
      ;; Remove possible residual \r (extra protection for some Windows environments)
      (if line
        (str/replace line "\r" "")
        line))
    (catch Exception e
      (println "[READ-ERROR] Exception reading line:" (.getMessage e))
      nil)))

;; In handle-client ensure exceptions do not kill connection handling loop
(defn handle-client [^Socket client-socket]
  (let [client-id (.toString (.getRemoteSocketAddress client-socket))
        ;; Explicitly use UTF-8 encoding, ensure consistency with Emacs side
        reader (BufferedReader. (InputStreamReader. (.getInputStream client-socket) StandardCharsets/UTF_8))
        writer (PrintWriter. (OutputStreamWriter. (.getOutputStream client-socket) StandardCharsets/UTF_8) true)
        local-running (atom true)]
    
    ;; Atomic operation to set connection, use lock to ensure thread safety
    (locking client-connection
      (when @client-connection
        (println "[CONN] Closing previous connection")
        (try (.close (:socket @client-connection)) (catch Exception _)))
      (reset! client-connection {:socket client-socket :reader reader :writer writer}))
    
    (println "[CONN] Handler started for:" client-id)
    
    (try
      ;; Notify connection established
      (handle-client-connected client-id)
      
      ;; Read loop: ensure connection is not interrupted even if single message processing fails
      (while (and @running @local-running)
        (when-let [line (read-line-robust reader)]
          (if (str/blank? line)
            (do
              ;; Empty line check, exit if EOF
              (when (nil? (.ready reader))
                (Thread/sleep 100)))
            (do
              (try
                (let [data (edn/read-string {:readers *data-readers*} line)]
                  (cond
                    ;; Handle sync return (response from Emacs)
                    (and (map? data) (= (:type data) :elisp-sync-return))
                    (when-let [promise (get @call-results (:id data))]
                      (deliver promise (:value data)))
                    
                    ;; Handle async call
                    (and (map? data) (= (:type data) :call-clojure-async))
                    (do (println "[RECV] Async call:" (:func data))
                        (handle-client-async-call data))
                    
                    ;; Handle sync call
                    (and (map? data) (= (:type data) :call-clojure-sync))
                    (do (println "[RECV] Sync call:" (:func data))
                        (handle-client-sync-call data))
                    
                    ;; Other types
                    :else
                    (handle-client-message data)))
                (catch Exception e
                  (println "[PARSE-ERROR] Failed to parse line:" (.getMessage e))
                  (println "[PARSE-ERROR] Line content:" (subs line 0 (min 100 (count line))))))
              ;; Check if connection is still valid
              (when (or (.isClosed client-socket) (.isInputShutdown client-socket))
                (reset! local-running false))))))
      
      (catch Exception e
        (println "[CONN] Client disconnected or error:" (.getMessage e))
        (.printStackTrace e))
      
      (finally
        (println "[CONN] Cleaning up connection for:" client-id)
        ;; Key: only clean up connection for current socket, do not clean if not current connection
        (locking client-connection
          (when (= (:socket @client-connection) client-socket)
            (reset! client-connection nil)))
        (try (.close client-socket) (catch Exception _))))))

(defn ^:export start-server [port]
  "Start the TCP server with robust error handling and automatic restart capability.
Binds to localhost to avoid Windows firewall prompts for public networks."
  (let [port-num (if (string? port) (Integer/parseInt port) port)
        ;; Explicitly bind to localhost to avoid Windows firewall issues
        server-socket (ServerSocket. port-num 50 (java.net.InetAddress/getByName "127.0.0.1"))]
    (println "Server started on port" port-num "bound to localhost")
    
    ;; Use daemon thread to ensure it does not prevent JVM exit
    (doto (Thread. 
           (fn []
             (try
               (while @running
                 (try
                   (when (nil? @client-connection)
                     (println "[SERVER] Waiting for connection on port" port-num)
                     (let [client-socket (.accept server-socket)]
                       (println "[SERVER] New connection accepted from:" (.getRemoteSocketAddress client-socket))
                       ;; Set TCP keepalive and timeout (important for Windows)
                       (.setKeepAlive client-socket true)
                       (.setTcpNoDelay client-socket true)
                       (.setSoTimeout client-socket 0) ;; Infinite timeout, handled by application layer
                       ;; Start independent thread for each client
                       (doto (Thread. #(handle-client client-socket))
                         (.setDaemon true)
                         (.start))))
                   (catch java.net.SocketTimeoutException e
                     ;; Timeout continue loop, check running status
                     )
                   (catch Exception e
                     (println "[SERVER] Accept error:" (.getMessage e))
                     (Thread/sleep 1000))))
               (catch Exception e
                 (println "Server accept loop error:" (.getMessage e))
                 (.printStackTrace e))
               (finally
                 (println "[SERVER] Closing server socket")
                 (try (.close server-socket) (catch Exception _)))))
           "Cloel-Server-Accept-Thread")
      (.setDaemon true)
      (.start))
    
    (println "Waiting for client connection...")))

;; Keep main thread alive (correction: avoid CPU usage, add connection health check)
(defn ^:export keep-alive []
  (println "Main thread entering keep-alive loop...")
  (loop []
    (Thread/sleep 1000)
    ;; Check connection health status (on Windows connection may close abnormally without triggering exception)
    (when-let [conn @client-connection]
      (try
        (when (or (.isClosed (:socket conn))
                  (.isInputShutdown (:socket conn))
                  (.isOutputShutdown (:socket conn)))
          (println "[KEEP-ALIVE] Detected closed connection, resetting")
          (reset! client-connection nil))
        (catch Exception e
          (println "[KEEP-ALIVE] Error checking connection health:" (.getMessage e))
          (reset! client-connection nil))))
    (when @running
      (recur))))

(defn ^:export stop-server []
  "Gracefully stop the server and close all connections."
  (reset! running false)
  (when-let [conn @client-connection]
    (try
      (.close (:socket conn))
      (catch Exception e
        (println "[STOP] Error closing client connection:" (.getMessage e))))
    (reset! client-connection nil))
  (println "Server stopped."))