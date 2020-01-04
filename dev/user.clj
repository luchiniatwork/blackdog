(ns user
  (:require [blackdog.core :as bd]))

#_(def board (bd/connect-board! "/dev/cu.SLAB_USBtoUART"))

#_(bd/send-command board "os.version()")

#_(bd/send-command board "os.ls()")

#_(bd/send-command board "os.remove(\"touch.lua\")")

#_(bd/send-command board "os")

#_(bd/write-file! board "touch.lua")

#_(bd/send-command board "os.cat(\"touch.lua\")")

#_(bd/disconnect-board! board)
