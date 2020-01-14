(ns blackdog.utils
  (:require [clojure.java.io :as io]
            [me.raynes.fs :as fs]))

(defn valid-file?
  ([file]
   (valid-file? (constantly true) file))
  ([pred file]
   (let [ff (io/file file)]
     (and (fs/exists? ff)
          (not (fs/directory? ff))
          (pred ff)))))

(defn valid-files
  [pred from]
  (->> from io/file file-seq
       (filter #(valid-file? pred %))))

(defn select-from
  [dirs file]
  (let [cartesian (for [dir dirs
                        p (fs/parents file)]
                    (vector (fs/absolute dir) p))]
    (->> cartesian
         (filter #(= (first %) (second %)))
         ffirst)))

(defn sanitized-file-src-path
  [from file]
  (let [drop-n (-> from io/file .getAbsolutePath fs/split count)
        file-sub-path (->> file .getAbsolutePath fs/split (drop drop-n))]
    (.getPath (apply io/file file-sub-path))))

(defn sanitized-file-to-path
  [from to file]
  (let [drop-n (-> from io/file .getAbsolutePath fs/split count)
        file-sub-path (->> file .getAbsolutePath fs/split (drop drop-n))]
    (.getPath (apply io/file (into [to] file-sub-path)))))
