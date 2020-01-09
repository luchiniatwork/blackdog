(ns blackdog.core
  (:require [blackdog.plugin-fennel :as fennel]
            [clojure.core.async :refer [<! >! <!! >!! go go-loop chan]]
            [clojure.java.io :as io]
            [clojure.string :as s]
            [hawk.core :as hawk]
            [me.raynes.fs :as fs]
            [serial.core :as serial]
            [serial.util :as util]))

(def ^:private chunksize 255)

(def ^:private state (atom 'pending))

(def ^:private entry (atom ""))

(def ^:private crlf (atom :none))

(def ^:private watchers (atom nil))

(defn ^:private log [& args]
  #_(apply println args))

(defn ^:private reset-state! []
  (reset! state 'pending)
  (reset! entry "")
  (reset! crlf :none))

(defn ^:private transition-to!
  ([to]
   (transition-to! to nil))
  ([to opts]
   (let [from @state]
     (if (or (and (or (= from 'pending)
                      (= from 'ready)
                      (= from 'cmd)
                      (= from 'ready-to-receive))
                  (= to 'ready))

             (and (= from 'ready)
                  (= to 'cmd))

             (and (= from 'ready)
                  (= to 'receive-mode))

             (and (= from 'receive-mode)
                  (= to 'waiting-receive-ack))
             
             (and (= from 'ready)
                  (= to 'ready-to-receive))

             (and (= from 'ready-to-receive)
                  (= to 'waiting-receive-ack))

             (and (= from 'waiting-receive-ack)
                  (= to 'ready-to-receive)))
       (reset! state (vary-meta to assoc :opts opts))
       (throw (ex-info "Illegal transition" {:from from :to to}))))))

(declare ^:private force-ready!)

(defn ^:private wait-for [board target-state]
  (let [start (. System (nanoTime))
        retry-count (atom 0)]
    (loop []
      (log "waiting for" target-state "while it's" @state "at"
           (/ (- (. System (nanoTime)) start) 1000000.0)
           "ms")
      (Thread/sleep 5)
      (when (>= (- (. System (nanoTime)) start)
                500000000.0)
        (if (< @retry-count 5)
          (do (swap! retry-count inc)
              (force-ready! board))
          (throw (ex-info "Timed out" {:waiting-for target-state}))))
      (if-not (= @state target-state)
        (recur)))))

(defn ^:private send-command* [{:keys [port] :as board} cmd]
  (let [raw-cmd (.getBytes (str cmd "\r\n"))]
    (serial/write port raw-cmd)))

(defn ^:private force-ready! [board]
  (send-command* board "")
  (wait-for board 'ready))

(defn ^:private start-receive-mode! [{:keys [port] :as board} file-name]
  (wait-for board 'ready)
  (let [cmd (str "io.receive(\"" file-name "\")")]
    (transition-to! 'receive-mode {:cmd cmd})
    (serial/write port (.getBytes (str cmd "\r")))))

(defn ^:private write-chunk! [{:keys [port] :as board} n buf]
  (wait-for board 'ready-to-receive)
  (log "writing chunk")
  (serial/write port (byte-array [n]))
  (serial/write port (->> buf
                          (take n)
                          byte-array))
  (transition-to! 'waiting-receive-ack))

(defn ^:private finalize-file! [{:keys [port] :as board}]
  (wait-for board 'ready-to-receive)
  (log "finalizing file")
  (serial/write port (byte-array [0]))
  (force-ready! board))

(defn ^:private parse-rx [x entries-chan]
  (cond
    ;; cr has already seen and now lf, so crlf is true
    (and (= :cr @crlf) (= x 10))
    (reset! crlf :crlf)

    ;; we saw a cr but this is not an lf, so we reset to the start
    (and (= :cr @crlf) (not= x 10))
    (do (swap! entry #(str % "\r" (char x)))
        (reset! crlf :none))

    ;; a cr! cool!
    (= x 13)
    (reset! crlf :cr)

    ;; control char has already been seen and now lf, so crlf is control-ack (confirmed)
    (and (= :control-start @crlf) (= x 10))
    (reset! crlf :control-ack)

    ;; we saw a control before but this is not an lf, so we reset to the start
    (and (= :control-start @crlf) (not= x 10))
    (do (swap! entry #(str % "C" (char x)))
        (reset! crlf :none))

    ;; a "C" represents a potential control
    (= x 67)
    (reset! crlf :control-start)

    :otherwise
    (swap! entry #(str % (char x))))
  (when (or (= @crlf :crlf) (= @crlf :control-ack))
    (do (log "Putting entry in queue")
        (case @crlf
          :crlf
          (let [entry-out @entry]
            (>!! entries-chan entry-out))
          :control-ack
          (>!! entries-chan :control))
        (reset! entry "")
        (reset! crlf :none))))

(defn ^:private parse-entry [e]
  (log (str "Parsing: '" e "'\n")
       (str " - meta cmd: '" (-> @state meta :opts :cmd) "'\n")
       (str " - state: " @state))
  (let [trimmed-e (-> e
                      (s/replace-first "\n" "")
                      (s/replace-first "\r" ""))
        state-cmd (-> @state meta :opts :cmd)
        state-out-chan (-> @state meta :opts :out-chan)]
    (cond
      ;; During receive, we just got a control and should transition to
      ;; ready to receive
      (= :control e)
      (transition-to! 'ready-to-receive)

      ;; Prompt found after a command. We should be ready and inform command
      (and (re-matches #"^\/.* \> $" trimmed-e)
           (= 'cmd @state))
      (do (>!! state-out-chan :done)
          (transition-to! 'ready))

      ;; Prompt found. We should be ready
      (re-matches #"^\/.* \> $" trimmed-e)
      (transition-to! 'ready)

      ;; We are parsing a command and it's not the echo so it's console
      ;; out. Inform command.
      (and (= 'cmd @state)
           (not= state-cmd trimmed-e))
      (>!! state-out-chan trimmed-e)

      ;; If it's in receive-mode and it's an echo, we can start waiting
      (= 'receive-mode @state)
      (when (= state-cmd trimmed-e)
        (transition-to! 'waiting-receive-ack))

      ;; Sometimes it's just a padding line
      (= "" trimmed-e)
      (do)

      ;; Other times we have no idea what's happening
      :otherwise
      (do
        (log "UNKNOWN!" (-> e .getBytes vec))))))

(defn ^:private rx-listener [rx-chan]
  (fn [is]
    (let [buf (byte-array chunksize)
          n (.read is buf)
          parsed (->> buf
                      (take n)
                      byte-array
                      String.)]
      (go (doseq [b (->> buf
                         (take n)
                         byte-array)]
            (>! rx-chan b)))
      (log "\n====\nRead" n "bytes")
      #_(log " =" (->> buf
                       (take n)
                       byte-array
                       vec))
      #_(log " =" (->> buf
                       (take n)
                       byte-array
                       String.)))))

(defn send-command! [{:keys [port] :as board} cmd]
  (wait-for board 'ready)
  (let [out-chan (chan)]
    (transition-to! 'cmd {:cmd cmd
                          :out-chan out-chan})
    (send-command* board cmd)
    (println (<!! (go-loop [out []]
                    (let [i (<! out-chan)]
                      (if (= :done i)
                        (s/join "\n" out)
                        (recur (conj out i)))))))))

(defn ^:private mk-remote-dirs!
  [board dst]
  (let [parts (drop-last (s/split dst #"\/"))]
    (loop [accum []
           part (first parts)
           r (rest parts)]
      (let [new-accum (conj accum part)
            path (s/join "/" new-accum)]
        (when-not (= "" path)
          (when (= "false\n" (with-out-str
                               (send-command! board
                                              (str "os.exists(\"" path "\")"))))
            (send-command! board (str "os.mkdir(\"" path "\")"))))
        (when-not (empty? r)
          (recur new-accum (first r) (rest r)))))))

(defn write-file!
  ([board file-name]
   (write-file! board file-name file-name))
  ([board src-file-name dst-file-name]
   (mk-remote-dirs! board dst-file-name)
   (let [fis (io/input-stream (io/file src-file-name))]
     (print "Writing:" src-file-name "as" dst-file-name " ")
     (start-receive-mode! board dst-file-name)
     (loop []
       (let [buf (byte-array chunksize)
             n (.read fis buf)]
         (print ".")
         (if (pos? n)
           (do (log "Read and will write" n "bytes")
               (write-chunk! board n buf)
               (recur))
           (finalize-file! board)))))
   (println "")
   true))

(defn connect-board! [port-id]
  (reset-state!)
  (let [port (serial/open port-id)
        rx-chan (chan)
        entries-chan (chan)]

    (serial/listen! port (rx-listener rx-chan))

    (go-loop []
      (let [x (<! entries-chan)]
        (parse-entry x))
      (recur))

    (go-loop []
      (parse-rx (<! rx-chan) entries-chan)
      (recur))

    (let [board {:port port
                 :rx-chan rx-chan
                 :entries-chan entries-chan}]
      (force-ready! board)
      board)))

(defn stop-watch-dir! []
  (if-not @watchers
    (println "No watcher initialized to stop!")
    (do (hawk/stop! (:inbound @watchers))
        (hawk/stop! (:outbound @watchers))
        (reset! watchers nil)
        true)))

(defn disconnect-board! [{:keys [port] :as board}]
  (when @watchers
    (stop-watch-dir!))
  (serial/close! port)
  true)

(defn ^:private valid-file?
  ([file]
   (valid-file? (constantly true) file))
  ([pred file]
   (let [ff (io/file file)]
     (and (not (fs/directory? ff))
          (pred ff)))))

(defn ^:private valid-files
  [pred from]
  (->> from io/file file-seq
       (filter #(valid-file? pred %))))

(defn ^:private sanitized-file-src-path
  [from file]
  (let [drop-n (-> from io/file .getAbsolutePath fs/split count)
        file-sub-path (->> file .getAbsolutePath fs/split (drop drop-n))]
    (.getPath (apply io/file file-sub-path))))

(defn ^:private sanitized-file-to-path
  [from to file]
  (let [drop-n (-> from io/file .getAbsolutePath fs/split count)
        file-sub-path (->> file .getAbsolutePath fs/split (drop drop-n))]
    (.getPath (apply io/file (into [to] file-sub-path)))))

(defn ^:private copy-file-to!
  [from to file]
  (println "Preparing:" (sanitized-file-src-path from file))
  (fs/copy+ file (sanitized-file-to-path from to file)))

(defn ^:private copy-dir-to!
  ([from to]
   (copy-dir-to! (constantly true) from to))
  ([pred from to]
   (doseq [file (valid-files pred from)]
     (copy-file-to! from to file))))

(defn ^:private transpile-file-to!
  [transpile-f from to file]
  (println "Transpiling:" (sanitized-file-src-path from file))
  (transpile-f from to file))

(defn ^:private transpile-dir-to!
  [pred transpile-f from to]
  (doseq [file (valid-files pred from)]
    (transpile-file-to! transpile-f from to file)))

(defn ^:private inbound-handler
  [from to]
  (fn [ctx {:keys [file kind]}]
    (when-not (or (fs/directory? file) (= :delete kind))
      (println "Change detected:" (sanitized-file-src-path from file))
      (cond
        (valid-file? fennel/matcher file)
        (transpile-file-to! fennel/transpile from to file)

        (valid-file? file)
        (copy-file-to! from to file)))
    ctx))

(defn ^:private outbound-handler
  [to board]
  (fn [ctx {:keys [file kind]}]
    (when-not (or (fs/directory? file) (= :delete kind))
      (write-file! board
                   (.getPath file)
                   (sanitized-file-src-path to file)))
    ctx))

(defn ^:private write-all-out!
  [board to]
  (doseq [file (->> to io/file file-seq (filter fs/file?))]
    (let [pretty-file (sanitized-file-src-path to file)]
      (write-file! board (.getPath file) pretty-file))))

(defn ^:private initialize-dir!
  [dir board {:keys [out-dir clean-out?]
              :or {out-dir "out/"
                   clean-out? true}
              :as opts}]
  (println "Watching dir:" dir)
  (println "Working dir:" out-dir)
  (when (and clean-out? (fs/exists? out-dir))
    (println "Cleaning:" out-dir)
    (fs/delete-dir out-dir))
  (copy-dir-to! #(not (fennel/matcher %))
                dir out-dir)
  (transpile-dir-to! fennel/matcher
                     fennel/transpile
                     dir out-dir)
  (write-all-out! board out-dir)
  (reset! watchers {:inbound (hawk/watch! [{:paths [dir]
                                            :handler (inbound-handler dir out-dir)}])
                    :outbound (hawk/watch! [{:paths [out-dir]
                                             :handler (outbound-handler out-dir board)}])})
  true)

(defn watch-dir!
  ([board dir]
   (watch-dir! board dir nil))
  ([board dir opts]
   (if-not @watchers
     (initialize-dir! dir board opts)
     (println "A watcher is already running!"))))

(comment
  (def board (connect-board! "/dev/cu.SLAB_USBtoUART"))

  (send-command! board "os.version()")

  (send-command! board "os.ls()")

  (send-command! board "os.remove(\"touch.lua\")")

  (send-command! board "os")

  (write-file! board "touch.lua")

  (send-command! board "os.cat(\"touch.lua\")")

  (disconnect-board! board))
