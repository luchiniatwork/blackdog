(thread.start
 (fn []
   (pio.pin.setdir pio.OUTPUT pio.GPIO2)
   (pio.pin.setpull pio.NOPULL pio.GPIO2)
   (pio.pin.setdir pio.OUTPUT pio.GPIO4)
   (pio.pin.setpull pio.NOPULL pio.GPIO4)
   (while true
     (pio.pin.sethigh pio.GPIO2)
     (pio.pin.setlow pio.GPIO4)
     (tmr.delayms 500)
     (pio.pin.setlow pio.GPIO2)
     (pio.pin.sethigh pio.GPIO4)
     (tmr.delayms 250))))
