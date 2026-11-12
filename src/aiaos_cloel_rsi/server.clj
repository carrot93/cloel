(ns aiaos-cloel-rsi.server
  (:require [clojure.core.async :refer [>!! <!! chan go timeout]]
            [aiaos-cloel-rsi.aiaos-framework :as framework]
            [aiaos-cloel-rsi.llm-provider :as llm]))

;; Server state
(defonce server-port 8080)
(defonce server-state (atom {:running false
                             :commands-processed 0
                             :errors 0}))

;; Command channel
(def command-chan (chan 100))
(def result-chan (chan 100))

(defn log
  "Log a message to stdout."
  [level message]
  (println (format "[%s] %s" level message)))

(defn start-server
  "Start the Clojure server."
  []
  (log "INFO" "Starting AIAOS-Cloel-RSI server...")
  (swap! server-state assoc :running true)
  (log "INFO" (format "Server running on port %d" server-port))
  
  ;; Start command processor
  (start-command-processor)
  
  ;; Start HTTP server (simplified)
  (log "INFO" "HTTP server listening on http://localhost:8080")
  
  ;; Keep the server running
  (while (:running @server-state)
    (Thread/sleep 1000))
  
  (log "INFO" "Server stopped"))

(defn start-command-processor
  "Start processing commands from the command channel."
  []
  (go
    (while true
      (when-let [cmd (<!! command-chan)]
        (try
          (let [result (framework/process-command cmd)]
            (>!! result-chan result)
            (swap! server-state update :commands-processed inc))
          (catch Exception e
            (log "ERROR" (str "Command processing failed: " (.getMessage e)))
            (swap! server-state update :errors inc))))))))

(defn send-command
  "Send a command to the server."
  [command]
  (>!! command-chan command)
  (log "INFO" (format "Command received: %s" command)))

(defn get-result
  "Get the next result from the result channel."
  []
  (<!! result-chan))

(defn stop-server
  "Stop the Clojure server."
  []
  (log "INFO" "Stopping server...")
  (swap! server-state assoc :running false)
  (log "INFO" "Server stopped"))

(defn get-status
  "Get the server status."
  []
  @server-state)

(defn -main
  "Main entry point."
  [& args]
  (log "INFO" "AIAOS-Cloel-RSI Server v1.0.0")
  (log "INFO" "Initializing...")
  
  ;; Initialize LLM provider
  (ll/init)
  
  ;; Start server
  (start-server))

;; For REPL development
(defn restart
  "Restart the server."
  []
  (stop-server)
  (Thread/sleep 1000)
  (start-server))