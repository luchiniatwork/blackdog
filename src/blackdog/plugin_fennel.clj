(ns blackdog.plugin-fennel
  (:require [me.raynes.fs :as fs]
            [clojure.java.io :as io]))

(defn matcher [f]
  (= ".fnl" (fs/extension f)))

(defn transpile [from to f]
  (let [from-f (io/file from)
        drop-n (count (fs/split from-f))
        file-sub-path (drop-last (drop drop-n (fs/split f)))
        out-name (str (->> f fs/split-ext first) ".lua")
        out-file (apply io/file (into [to] (concat file-sub-path [out-name])))
        cmd ["fennel" "--compile"
             (.getAbsolutePath f)]]
    (let [{:keys [exit out err]} (apply fs/exec cmd)]
      (if (not= 0 exit)
        (do (println "\nError transpiling" (.getPath (apply io/file file-sub-path)) "\n")
            (println err))
        (do (fs/mkdirs (fs/parent out-file))
            (spit out-file out))))))
