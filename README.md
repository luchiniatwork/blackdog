[lua-rtos]: https://github.com/whitecatboard/Lua-RTOS-ESP32
[whitecat]: https://github.com/whitecatboard/whitecat-console
[fennel]: https://fennel-lang.org/
[clojure]: https://clojure.org/
[esp32-door]: https://github.com/luchiniatwork/esp32-door-alarm
[esp32]: http://esp32.net/
[usb-uart]: https://www.silabs.com/products/development-tools/software/usb-to-uart-bridge-vcp-drivers

# Blackdog

Blackdog is a highly experimental approach and workflow to code for
development boards platforms by [Lua RTOS][lua-rtos].

## Motivation

[Lua RTOS][lua-rtos] is a real-time operating system designed to run
on embedded systems, with minimal requirements of FLASH and RAM
memory. Currently Lua RTOS is available for ESP32, ESP8266 and PIC32MZ
platforms.

It is a delightfully fun OS to play with on an equally fun
platform. However, the development workflow is a bit cumbersome:

1. A terminal session can be open with something along the lines of
  `picocom --baud 115200 /dev/tty.SLAB_USBtoUART` which gives a nice
  shell to interact with and explore the OS.
2. This terminal connection must be closed though if you want to
   upload or download files from the platform (through the `wcc -up`
   or `wcc -down` commands).
3. In practice if you are exploring something and need your code on
   the board, you'll need to exit picocom, run wcc, rejoin picocom and
   then resume your exploration.
4. This workflow becomes even more challenging if you want to segment
   your code (as you should) into multiple reusable components - the
   task of making sure the correct files are in the correct place
   would be all on your shoulders.
5. Last but not least, Lua is a great fun language. It is so great
   that it is often used as a target environment of other languages
   (i.e. [Fennel][fennel]). Wouldn't it be great to code in Fennel and
   deploy in Lua without the need of running the whole Fennel on a
   very limited device?

Blackdog tackles these problems by exposing a very simple terminal
interface that can be used from a [Clojure][clojure] REPL.

This terminal can be expanded to watch for changes in one or more
directories. Once changes take place, the terminal connection remains
open and the files and/or directories get transferred to the
development board.

As part of the workflow, Blackdog also detects [Fennel][fennel] files
and transpiles them before sending them to the board.

## Example

The [esp32-door-alarm][esp32-door-alarm] is a simple example of what
can be easily achieved.

## Getting started

You'll need:

- A compatible development board (an [ESP32][esp32] is recommended)
- A USB to UART Driver (most probably [this one][usb-uart] for a basic
  [ESP32][esp32])
- [Whitecat console][whitecat]
- [Clojure][clojure]
- Optional 1: `picocom` (or similar) - for terminal access
- Optional 2: [Fennel][fennel] - if you want to transpile Fennel files

Follow the instructions to get [Lua RTOS][lua-rtos] flashed into your
board. Favor flashing it with the default file system because it
carries useful libraries you might want to use:

``` shell
$ wcc -p /dev/cu.SLAB_USBtoUART -f -ffs
```

Create a `deps.edn` file pointing to the latest version of Blackdog:

``` clojure
{:paths ["dev"]

 :deps {blackdog {:mvn/version "0.1.0"}}
```

To interact with the terminal via the REPL it's recommended you create
a `dev/user.clj` file that requires Blackdog:

``` clojure
(ns user
  (require [blackdog.core :as bd]))
```

You can create a `src` folder to keep your Lua, Fennel, or other
assets that you want to be transferred to your board. A sample
directory can be found at `sample/`.

Blackdog has a very simple public interface.

## Connecting/disconnecting

Connecting:

``` clojure
(def board (bd/connect-board! "/dev/tty.SLAB_USBtoUART"))
```

Disconnecting:

``` clojure
(bd/disconnect-board! board)
```

## Sending Commands

List the contents of `examples/lua` with the command `os.ls("examples/lua")`:

``` clojure
(bd/send-command! board "os.ls(\"examples/lua\")")
```

List threads:

``` clojure
(bd/send-command! board "thread.list()")
```

Run the a file called `play.lua`:

``` clojure
(bd/send-command! board "dofile(\"play.lua\")")
```

You get the picture. You send a command and see the return as if it
was a REPL/terminal kind of integration.

## Writing Files

You can send a single file with the following function. In this
example a file called `touch.lua` is written to the board:

``` clojure
(bd/write-file! board "touch.lua")
```

There's also a `<src> <dst>` version of this function.

## Automated Workflow

Blackdog's final public function is `watch-dir!` which is also the
most powerful.

``` clojure
(bd/watch-dir! board "src")
```

The call above will create a watch on path `src`. Every additional
change (changed files, new files, new directories) will have their
effects replicated onto the board. The exception is removal - for
simplicity and experience sake, removal was not implemented.

Multiple folders can be watched at the same time:

``` clojure
(bd/watch-dir! board "src" "esp32-shared/src")
```

Any files put on `src` or `esp32-shared/src` will be copied over.

**Attention**: `watch-dir!` copies files as they are as long as their
extension is not `.fnl`. For `.fnl` files, `watch-dir!` tries to
transpile them first into `.lua` files using your installed
[Fennel][fennel].

By default `watch-dir!` creates a working directory called `out` where
it temporally saves the files to be written on the board. This local
working directory, by default, is also removed every time the
`watch-dir!` function is called. These can be overwritten with:

``` clojure
(bd/watch-dir! board "src" {:out-dir "working-dir"
                            :clean-out? false})
```
