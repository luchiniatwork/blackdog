(ns blackdog.core
  (:require [blackdog.plugin-fennel :as fennel]
            [clojure.core.async :refer [<! >! <!! >!! go go-loop chan]]
            [clojure.java.io :as io]
            [clojure.string :as s]
            [juxt.dirwatch :as watch]
            [me.raynes.fs :as fs]
            [serial.core :as serial]
            [serial.util :as util]))

(def ^:private chunksize 255)

(def ^:private state (atom 'pending))

(def ^:private entry (atom ""))

(def ^:private crlf (atom :none))

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
    (transition-to! 'receive-mode cmd)
    (serial/write port (.getBytes (str cmd "\r")))))

(defn ^:private write-chunk! [{:keys [port] :as board} n buf]
  (wait-for board 'ready-to-receive)
  (log "writing chunk")
  (serial/write port (byte-array [n]))
  (Thread/sleep 50)
  (serial/write port (->> buf
                          (take n)
                          byte-array))
  (transition-to! 'waiting-receive-ack))

(defn ^:private finalize-file! [{:keys [port] :as board}]
  (wait-for board 'ready-to-receive)
  (log "finalizing file")
  (serial/write port (byte-array [0]))
  (force-ready! board)
  #_(transition-to! 'ready))

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
  (log (str "Parsing: '" e "' - meta: '" (-> @state meta :opts) "'"))
  (cond
    (= "/ > " e)
    (transition-to! 'ready)

    (and (= 'cmd @state)
         (not= (-> @state meta :opts) e))
    (println e)

    (and (= 'cmd @state)
         (= (-> @state meta :opts) e))
    (println "")

    (= 'receive-mode @state)
    (when (= (-> @state meta :opts) e)
      (transition-to! 'waiting-receive-ack))

    (= :control e)
    (transition-to! 'ready-to-receive)

    (= "" e)
    (do)

    :otherwise
    (do
      (log "UNKNOWN!" (-> e .getBytes vec)))))

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
      (log " =" (->> buf
                     (take n)
                     byte-array
                     vec))
      (log " =" (->> buf
                     (take n)
                     byte-array
                     String.)))))

(defn send-command [{:keys [port] :as board} cmd]
  (wait-for board 'ready)
  (transition-to! 'cmd cmd)
  (send-command* board cmd)
  nil)

(defn write-file!
  ([board file-name]
   (write-file! board file-name file-name))
  ([board src-file-name dst-file-name]
   (let [fis (io/input-stream (io/file src-file-name))]
     (println "\nsrc:" src-file-name "-> dst:" dst-file-name)
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

(defn disconnect-board! [{:keys [port] :as board}]
  (serial/close! port)
  true)


(defn ^:private copy-to
  ([from to]
   (copy-to (constantly true) from to))
  ([pred from to]
   (let [from-f (io/file from)
         drop-n (count (fs/split from-f))]
     (doseq [file (->> from io/file file-seq
                       (filter #(not (fs/directory? %)))
                       (filter pred))]
       (let [file-sub-path (drop drop-n (fs/split file))]
         (println "Preparing:" (.getPath (apply io/file file-sub-path)))
         (fs/copy+ file
                   (apply io/file (into [to] file-sub-path))))))))

(defn ^:private transpile-to
  [pred transpile-f from to]
  (let [from-f (io/file from)
        drop-n (count (fs/split from-f))]
    (doseq [file (->> from io/file file-seq
                      (filter #(not (fs/directory? %)))
                      (filter pred))]
      (let [file-sub-path (drop drop-n (fs/split file))]
        (println "Transpiling:" (.getPath (apply io/file file-sub-path)))
        (transpile-f file from to)))))

(defn watch-dir
  ([dir]
   (watch-dir dir nil))
  ([dir {:keys [out-dir clean-out?]
         :or {out-dir "out/"
              clean-out? true}
         :as opts}]
   (println "\nWatching dir:" dir)
   (println "Working dir:" out-dir)
   (when (and clean-out? (fs/exists? out-dir))
     (println "Cleaning:" out-dir)
     (fs/delete-dir out-dir))
   (copy-to #(not (fennel/matcher %))
            dir out-dir)
   (transpile-to fennel/matcher
                 fennel/transpile
                 dir out-dir)))

(comment
  (def board (connect-board! "/dev/cu.SLAB_USBtoUART"))

  (send-command board "os.version()")

  (send-command board "os.ls()")

  (send-command board "os.remove(\"touch.lua\")")

  (send-command board "os")

  (write-file! board "touch.lua")

  (send-command board "os.cat(\"touch.lua\")")

  (disconnect-board! board))
