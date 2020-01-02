(ns user
  (:require [serial.core :as serial]
            [serial.util :as util]
            [clojure.java.io :as io]
            [clojure.string :as s]
            [clojure.core.async :refer [<! >! <!! >!! go go-loop chan pipeline]]))

#_(defn read-is [^java.io.InputStream is]
    (let [bufsize 8192
          buf (byte-array bufsize)]
      (loop [cout ""]
        (let [n (.read is buf)
              this-bit (->> buf
                            (take n)
                            byte-array
                            String.)]
          #_(println (take-last 2 this-bit))
          (println "\n= 13 10"
                   (= 13 (byte (first (take-last 2 this-bit))))
                   (= 10 (byte (last (take-last 2 this-bit)))))
          (println "first" (byte (first (take-last 2 this-bit))))
          (println "last" (byte (last (take-last 2 this-bit))))
          
          (if (or (pos? (.available is))
                  (not= '(13 10) (map byte (take-last 2 this-bit))))
            (recur (str cout this-bit))
            (str cout (apply str (drop-last 2 this-bit))))))))

#_(defn read-is2 [^java.io.InputStream is]
    (let [bufsize 8192
          buf (byte-array bufsize)]
      (loop [cout ""]
        (let [n (.read is buf)
              out (str cout
                       (->> buf
                            (take n)
                            byte-array
                            String.))]
          (if (pos? (.available is))
            (recur out)
            out)))))


#_(defn cmd-splitter-xform
    [rf]
    (println "\n= xform")
    (let [cmd (volatile! "")
          crlf (volatile! 0)]
      (fn
        ([] (println "- 0 args") (rf))
        ([result]
         (println "- 1 args")
         (if-not (= @cmd "")
           result
           (let [cmd-out @cmd]
             (vreset! cmd "")
             (vreset! crlf 0)
             (unreduced (rf result cmd-out)))))
        ([result input]
         (println "- 2 args (result:" result "input:" input ", cmd:" @cmd ")")
         (cond
           (and (= 1 @crlf) (= input 10))
           (vreset! crlf 2)
           
           (= input 13)
           (vreset! crlf 1)

           :otherwise
           (vswap! cmd #(str % (char input))))
         
         (if (= @crlf 2)
           (let [cmd-out @cmd]
             (vreset! cmd "")
             (vreset! crlf 0)
             (let [step (rf result cmd-out)]
               step))
           #_result
           nil)))))


;; should yield ["io.send(\"system.lua\")" "foo" and leave bar [98 97 114] pending
#_(eduction cmd-splitter-xform [105 111 46 115 101
                                110 100 40 34 115 121
                                115 116 101 109 46 108
                                117 97 34 41 13 10 102
                                111 111 13 10 98 97 114])


(def ^:private chunksize 255)

(def ^:private state (atom 'pending))

(def ^:private entry (atom ""))

(def ^:private crlf (atom :none))

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
                      (= from 'cmd)
                      (= from 'receive-mode))
                  (= to 'ready))

             (and (= from 'ready)
                  (= to 'cmd))

             (and (= from 'ready)
                  (= to 'receive-mode))

             (and (= from 'receive-mode)
                  (= to 'ready-to-receive))
             
             (and (= from 'ready-to-receive)
                  (= to 'receive-mode)))
       (reset! state (vary-meta to assoc :opts opts))
       (throw (ex-info "Illegal transition" {:from from :to to}))))))

(defn ^:private wait-for [target-state]
  (loop []
    (if-not (= @state target-state)
      (recur))))

(defn ^:private send-command* [{:keys [port] :as board} cmd]
  (let [raw-cmd (.getBytes (str cmd "\r\n"))]
    (serial/write port raw-cmd)))

(defn send-command [{:keys [port] :as board} cmd]
  (wait-for 'ready)
  (transition-to! 'cmd cmd)
  (send-command* board cmd))

(defn ^:private start-receive-mode! [{:keys [port] :as board} file-name]
  (wait-for 'ready)
  (transition-to! 'receive-mode)
  (let [cmd (str "io.receive(\"" file-name "\")\r")]
    (serial/write port (.getBytes cmd))))

(defn ^:private write-chunk! [{:keys [port] :as board} n buf]
  (wait-for 'ready-to-receive)
  (serial/write port (byte-array [n]))
  (serial/write port (->> buf
                          (take n)
                          byte-array)))

(defn ^:private finalize-file! [{:keys [port] :as board}]
  (wait-for 'ready-to-receive)
  (serial/write port (byte-array [0])))

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
           (do (println "Read and will write" n "bytes")
               (write-chunk! board n buf)
               (recur))
           (finalize-file! board)))))))

(defn ^:private parse-entry [e]
  #_(println (str "Parsing: '" e "'"))
  (cond
    (= "/ > " e)
    (transition-to! 'ready)

    (and (= 'cmd @state)
         (not= (-> @state meta :opts first) e))
    (do
      #_(println "Console out:" e))
    
    :otherwise
    (do
      #_(println "UNKNOWN!" e))))

(defn ^:private rx-listener [rx-chan]
  (fn [is]
    (let [buf (byte-array chunksize)
          n (.read is buf)
          parsed (->> buf
                      (take n)
                      byte-array
                      String.)]
      #_(println "Read" n "bytes")
      (go (doseq [b (->> buf
                         (take n)
                         byte-array)]
            (>! rx-chan b)))
      #_(println "\n====\nRead" n "bytes")
      #_(println " =" (->> buf
                           (take n)
                           byte-array
                           vec))
      #_(println " =" (->> buf
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
        #_(println "---" x "---")
        (parse-entry x))
      (recur))

    (go-loop []
      (let [x (<! rx-chan)]
        (print "Seeing" x "with crlf as" @crlf)
        (cond
          ;; cr has already seen and now lf, so crlf is true
          (and (= :cr @crlf) (= x 10))
          (reset! crlf :crlf)

          ;; we saw a cr but this is not an lf, so we reset to the start
          (and (= :cr @crlf) (not= x 10))
          (reset! crlf :none)

          ;; a cr! cool!
          (= x 13)
          (reset! crlf :cr)

          ;; control char has already been seen and now lf, so crlf is control-ack (confirmed)
          (and (= :control-start @crlf) (= x 10))
          (reset! crlf :control-ack)

          ;; we saw a control before but this is not an lf, so we reset to the start
          (and (= :control-start @crlf) (not= x 10))
          (reset! crlf :none)

          ;; a "C" represents a potential control
          (= x 67)
          (reset! crlf :control-start)
          
          :otherwise
          (swap! entry #(str % (char x))))
        (println " crlf now is" @crlf)
        (when (or (= @crlf :crlf) (= @crlf :control-ack))
          (do (case @crlf
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
      (send-command* board "")
      board)))

(defn disconnect-board! [{:keys [port] :as board}]
  (serial/close! port)
  true)

(def board (connect-board! "/dev/cu.SLAB_USBtoUART"))

(send-command board "os.version()")

#_(send-command board "os.ls()")

#_(send-command board "os")

#_(disconnect-board! board)

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
