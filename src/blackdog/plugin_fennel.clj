(ns blackdog.plugin-fennel
  (:require [blackdog.utils :as utils]
            [clojure.java.io :as io]
            [clojure.string :as s]
            [me.raynes.fs :as fs]))

(defn matcher [f]
  (= ".fnl" (fs/extension f)))

(defn transpile [from to f]
  (let [out-file (-> (utils/sanitized-file-to-path from to f)
                     (s/replace #"\.fnl$" ".lua"))
        cmd ["fennel" "--compile"
             (.getAbsolutePath f)]]
    (let [{:keys [exit out err]} (apply fs/exec cmd)]
      (if (not= 0 exit)
        (do (println "\nError transpiling" (utils/sanitized-file-src-path from f))
            (println err))
        (do (fs/mkdirs (fs/parent out-file))
            (spit out-file out))))))
