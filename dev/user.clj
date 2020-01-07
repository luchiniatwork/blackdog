(ns user
  (:require [blackdog.core :as bd]
            [clojure.java.io :as io]))

#_(bd/watch-dir "tests/src" board)

#_(bd/stop-watch-dir)


#_(def board (bd/connect-board! "/dev/cu.SLAB_USBtoUART"))

#_(bd/send-command board "os.version()")

#_(bd/send-command board "os.ls()")

#_(bd/send-command board "os.remove(\"touch.lua\")")

#_(bd/send-command board "os.cd(\"/\")")

#_(bd/send-command board "os.pwd()")

#_(bd/write-file! board "out/touch.lua" "touch.lua")

#_(bd/write-file! board "out/touch2.lua" "touch2.lua")

#_(bd/send-command board "os.mkdir(\"another-sub\")")

#_(bd/send-command board "os.mkdir(\"sub1/sub2/sub3\")")

#_(bd/write-file! board "out/another-sub/blink.lua" "another-sub2/blink333.lua")

#_(bd/send-command board "os.cat(\"blink.lua\")")

#_(with-out-str (bd/send-command board "os.exists(\"another-sub2\")"))

#_(bd/mk-remote-dirs board "sub1/sub2/sub3/blink333.lua")

#_(bd/disconnect-board! board)
