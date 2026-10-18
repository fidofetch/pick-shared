# The console

Every app (the engine, the Builder, the Studio inside it) has a command console. People type into it; agents send
the same commands from outside, so a running app can be driven while testing instead of being relaunched with a
new set of `-D` flags each time.

## Typing

**F1** or the **`** key (left of 1) opens and closes it. `help` lists the commands, `help <command>` shows one.
Up and Down go through earlier lines, Tab finishes a command's name, Esc closes the console. Several commands go
on one line, split by `;`, and run in order: `settings; wait 1; shot`. A line stops at its first error.

Lines an agent sent show as `agent> ...`, so you can see what was done to the app.

## From an agent or a script

Start the app with its console flag, then send lines with `tools/console.py` (standard library only; from an app's folder it is `../pick-shared/tools/console.py`):

| App | Flag | Port |
|---|---|---|
| Engine | `-Dvoxel.console` (or `=port`) | 47100 |
| Builder and Studio | `-Dbuilder.console` (or `=port`) | 47101 |

```
python3 tools/console.py "settings; text 200; wait 0.5; shot /tmp/settings200.png"
python3 tools/console.py --port 47101 "open 1; step bake; shot /tmp/bake-step.png"
python3 tools/console.py < script.txt       # a line per command line, stops at the first error
```

It prints each answer and exits 1 when a line failed, 2 when nothing is listening. Only this machine can connect
(the app listens on 127.0.0.1), and nothing listens unless the flag is set. `-Dvoxel.run="..."` and
`-Dbuilder.run="..."` run a line at start, for one-off scripted runs.

The protocol, for other clients: send one line, read lines back until `## ok` or `## error`.

## Commands that wait

Some commands end on a later frame, and the next command on the line waits for them:

- `shot [file]` saves the next frame without the console and answers with the file's path.
- `wait <s>` and `frames <n>`: let a change settle (a slide, a resize) before a shot.
- Engine `baked`: until the bricks around the camera are baked. Builder `finished`: until the run ends.

Engine settings: `get [section]` lists the saved settings, `set sky.haze 0.3` changes one and keeps it (the
section checks and clamps the value as it does at start).

Engine physics: `pile [n]`, `throw sphere|box`, `bodies [clear]` (counts and the step cost), `gravity [on|off]`.
- Builder `open`, `new`, `leave`: until the slide between the shelf and the editor ends.

## Adding a command

```java
commands.add("hud", "hud [on|off]", "show or hide the debug HUD", a -> { hud.setVisible(a.on(0, !hud.isVisible())); return null; });
```

The handler runs on the main thread, so it may touch anything. It returns its output (a String, or null), or a
`CompletableFuture<String>` when it ends later. `Args` reads words with errors that say what was wrong:
`str`, `num`, `on` (on/off), `pick` (an enum constant, listing the choices), `rest`. A thrown exception becomes
`error: <message>` plus the command's usage. The engine's commands are in `Game.addCommands`, the Builder's in
`BuilderMain.addCommands`. The console itself (`com.pick.console`) lives in
fidofetch/pick-shared, which both apps build from.
