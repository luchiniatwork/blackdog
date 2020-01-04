(ns user
  (:require [blackdog.board :as b]))

#_(def board (b/connect-board! "/dev/cu.SLAB_USBtoUART"))

#_(b/send-command board "os.version()")

#_(b/send-command board "os.ls()")

#_(b/send-command board "os.remove(\"touch.lua\")")

#_(b/send-command board "os")

#_(b/write-file! board "touch.lua")

#_(b/send-command board "os.cat(\"touch.lua\")")

#_(b/disconnect-board! board)
