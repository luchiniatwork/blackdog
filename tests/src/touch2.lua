-- ----------------------------------------------------------------
-- WHITECAT ECOSYSTEM
--
-- Lua RTOS examples
-- ----------------------------------------------------------------
-- Read blink a led in a thread. While led is blinking Lua
-- interpreter are available, you cant start new threads for
-- doing other things.
-- ----------------------------------------------------------------


thread.start(function()
      led = pio.GPIO4
      button = pio.GPIO19

      pio.pin.setdir(pio.OUTPUT, led)
      pio.pin.setpull(pio.NOPULL, led)
      pio.pin.setdir(pio.INPUT, button)

      while true do
         val = pio.pin.getval(button)
         if val == 1 then
            pio.pin.inv(led)
         end
      end
end)
