(ns user
  (:require [serial.core :as serial]
            [serial.util :as util]
            [clojure.java.io :as io]
            [clojure.string :as s]
            [clojure.core.async :refer [<! >! <!! >!! go go-loop chan pipeline]]))

(def ^:private chunksize 255)

(def ^:private state (atom 'pending))

(def ^:private entry (atom ""))

(def ^:private crlf (atom :none))

(defn ^:private log [& args]
  #_(apply println args))

(defn reset-state! []
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

(defn send-command [{:keys [port] :as board} cmd]
  (wait-for board 'ready)
  (transition-to! 'cmd cmd)
  (send-command* board cmd))

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
  (transition-to! 'ready))

(defn write-file!
  ([board file-name]
   (write-file! board file-name file-name))
  ([board src-file-name dst-file-name]
   (let [fis (io/input-stream (io/file src-file-name))]
     (start-receive-mode! board dst-file-name)
     (loop []
       (let [buf (byte-array chunksize)
             n (.read fis buf)]
         (if (pos? n)
           (do (log "Read and will write" n "bytes")
               (write-chunk! board n buf)
               (recur))
           (finalize-file! board)))))))

(defn ^:private parse-entry [e]
  (log (str "Parsing: '" e "' - meta: '" (-> @state meta :opts) "'"))
  (cond
    (= "/ > " e)
    (transition-to! 'ready)

    (= 'cmd @state)
    (when (not= (-> @state meta :opts) e)
      (println e))

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
      (let [x (<! rx-chan)]
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
                  (>! entries-chan entry-out))
                :control-ack
                (>! entries-chan :control))
              (reset! entry "")
              (reset! crlf :none))))
      (recur))

    (let [board {:port port
                 :rx-chan rx-chan
                 :entries-chan entries-chan}]
      (force-ready! board)
      board)))

(defn disconnect-board! [{:keys [port] :as board}]
  (serial/close! port)
  true)

#_(def board (connect-board! "/dev/cu.SLAB_USBtoUART"))

#_(send-command board "os.version()")

#_(send-command board "os.ls()")

#_(send-command board "os.remove(\"touch.lua\")")

#_(send-command board "os")

#_(write-file! board "touch.lua")

#_(send-command board "os.cat(\"touch.lua\")")

#_(disconnect-board! board)

#_(dotimes [n 500]
    (let [b (connect-board! "/dev/cu.SLAB_USBtoUART")]
      #_(Thread/sleep 10)
      (when-not (= @state 'ready)
        (println (str "State is " @state " instead of ready"))
        (Thread/sleep 500)
        (println (str "!!! State is " @state " after waiting 500ms"))
        (Thread/sleep 500)
        (println (str "!!! State is " @state " after waiting 500ms"))
        (Thread/sleep 500)
        (println (str "!!! State is " @state " after waiting 500ms"))
        (Thread/sleep 500)
        (println (str "!!! State is " @state " after waiting 500ms")))
      (disconnect-board! b)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;


(comment (def port (serial/open "/dev/cu.SLAB_USBtoUART"))

         (def leftover (atom ""))

         (def c (chan))

         (def carriage '(13 10))

         (serial/listen!
          port
          (fn [is]
            (let [bufsize 8192
                  buf (byte-array bufsize)
                  n (.read is buf)
                  parsed (->> buf
                              (take n)
                              byte-array
                              String.)]
              (println "\n====\nRead" n "bytes")
              (println " =" (->> buf
                                 (take n)
                                 byte-array
                                 vec))
              (println " =" (->> buf
                                 (take n)
                                 byte-array
                                 String.))
              (if (not= carriage (map byte (take-last 2 parsed)))
                (reset! leftover parsed)
                (doseq [part (s/split parsed (re-pattern (String. (byte-array carriage))))]
                  (let [out (str @leftover part)]
                    (go (>! c out)))
                  (when (not= @leftover "")
                    (reset! leftover "")))))))

         #_(serial/unlisten! port)

         #_(serial/write port (.getBytes "clear\r\n"))

         (serial/write port (.getBytes "os.version()\r\n"))

         #_(serial/write port (.getBytes "os.ls()\r\n"))

         #_(serial/write port (.getBytes "os.remove(\"test.txt\")\r\n"))

         #_(serial/write port (.getBytes "os.cat(\"system.lua\")\r\n"))

         #_(serial/write port (.getBytes "os\r\n"))


         ;; read file (board in send mode)
         (do
           (serial/write port (.getBytes "io.send(\"system.lua\")\r"))
           (Thread/sleep 10)
           (serial/write port (.getBytes "C\n")))


         ;; tries to write a bigger file
         (let [fis (io/input-stream (io/file "touch.lua"))]
           (serial/write port (.getBytes "io.receive(\"touch.lua\")\r"))
           (Thread/sleep 50)
           (loop []
             (let [bufsize 255
                   buf (byte-array bufsize)
                   n (.read fis buf)]
               (if (pos? n)
                 (do (println "Read and will write" n "bytes")
                     (serial/write port (byte-array [n]))
                     (Thread/sleep 50)
                     (serial/write port (->> buf
                                             (take n)
                                             byte-array))
                     (Thread/sleep 50)
                     (recur))
                 (serial/write port (byte-array [0])))
               )))


         ;; tries to write a bigger file
         (let [fis (io/input-stream (io/file "touch.lua"))]
           (start-receive-mode! board "touch.lua")
           (loop []
             (let [bufsize 255
                   buf (byte-array bufsize)
                   n (.read fis buf)]
               (if (pos? n)
                 (do (println "Read and will write" n "bytes")
                     (write-chunk! board n buf)
                     (recur))
                 (finalize-file! board))
               )))







         ;; write file (board in receive mode)
         (do
           (serial/write port (.getBytes "io.receive(\"test.txt\")\r"))
           (Thread/sleep 50)
           (serial/write port (byte-array [3]))
           (Thread/sleep 50)
           (serial/write port (.getBytes "foo"))
           (Thread/sleep 50)
           (serial/write port (byte-array [0])))

         (serial/read )


         (comment
           (serial/close! port)

           (go-loop []
             (let [x (<! c)]
               (println "---" x "---"))
             (recur))
           
           ))
