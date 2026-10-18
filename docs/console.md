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

The protocol, for other clients: send a line, read lines back until `## ok` or `## error`. Lines that start with
`# ` are pushed (a watch, a topic you follow) and belong to no answer. Lines may be sent without waiting: they run
in order and their answers come back in order.

## Staying connected: the live hook

One connection is one session, and it can stay open: an agent sets what it sends (commands, variables, keys) and
what comes back (the variables it watches, the topics it follows), then reads as things happen.

```
python3 tools/console.py -i                          # type lines, see answers and pushed lines as they come
python3 tools/console.py --for 5 "watch player.pos every 0.5; move forward 3"
printf 'subscribe progress run\nopen 1\nrun preview\n' | python3 tools/console.py --port 47101 -i --for 60
```

**Variables.** `vars [start]` lists them with what they are; `get <name or start...>` reads, `set <name> <value>`
changes one and answers with the value kept (a setting is clamped and saved as on its page). Saved settings
(`sky.haze`, `display.textScale`), every public field of the objects an app hands over (`render.sharpen`,
`env.sunSpeedDeg`, `view.distanceKm`), and the values an app adds by hand (`player.pos`, `run.stage`) are all
variables. A vector is written `x,y,z`.

**Watching.** `watch <names or starts...> [every <s>] [always]` pushes `# watch t=12.50 name=value ...` when a
value changed (checked every 0.25 s unless `every` says; `always` sends every time). `watch` lists yours,
`unwatch [name]` stops them.

**Topics.** `subscribe` lists them (a `*` marks yours); `subscribe <topic...>` has their lines pushed as
`# <topic> <text>`; `unsubscribe [topic...]`. Every app has `log` (what the console shows) and `out` (what the app
prints to its terminal).

**Aliases.** `alias <name> "<line>"` makes a command of your own; `$1`..`$9` are its words, `$*` all of them:
`alias walk "key w $1; get player.pos"`, then `walk 2`. `alias` lists them, `unalias <name>` removes one. They last
until the app closes.

When the connection ends, its watches stop and any key it held with `key <name> on` is let go.

### Engine (port 47100)

| | |
|---|---|
| `key <name> [seconds\|on\|off]` | press a key as if typed: a tap, held for seconds, or held until off. `keys` lists held ones, `keys release` lets go |
| `move <forward\|back\|left\|right\|up\|down>[+...] <s>` | walk or fly for some seconds (`move forward+left 2`); answers how far and where |
| `turn <deg right> [deg up] [s]` | turn the view by angles, at once or smoothly |
| `face <x,y,z>`, `tp <x,y,z>`, `tp up <m>` | look at a point; move the body to one |
| `click [left\|right]` | a mouse click (left builds with the tool) |

Variables: `player.*` (pos, alt, ground, speed, speedNow, yaw, pitch, bubble, bubbleRadius), `body.*` (the
player's body: gravity, noclip, velocity, grounded), `render.*`, `env.*` (sun and light), `atmo.*`, the saved
settings, `tool.name`, `tool.size`, `look.at`, `hud`, `vsync`, `paused`, `frame.ms`, `bake.busy`, `bodies.*`.
Topics: `bake` (each time the bricks around the camera are all baked). Movement needs the game unpaused
(`set paused off`), and keys reach the game only while the console is closed.

### Builder (port 47101)

Variables: `view.*` (yawDeg, pitchDeg, distanceKm for zoom, sunOn), `knob.<name>` (the open planet's settings, as
in All settings), `screen`, `planet`, `run.on`, `run.stage`, `run.progress`, `run.result`, `frame.index`,
`frame.count`. Topics: `progress` (the stage and how far, on change and every 5 s), `run` (started, ended with
its result), `runlog` (the run's log as it is written).

## Commands that wait

Some commands end on a later frame, and the next command on the line waits for them:

- `shot [file]` saves the next frame without the console and answers with the file's path.
- `wait <s>` and `frames <n>`: let a change settle (a slide, a resize) before a shot.
- Engine `baked`: until the bricks around the camera are baked. Builder `finished`: until the run ends.
- Builder `open`, `new`, `leave`: until the slide between the shelf and the editor ends.

Engine physics: `pile [n]`, `throw sphere|box`, `bodies [clear]` (counts and the step cost), `gravity [on|off]`.

## Adding a command or a variable

```java
commands.add("hud", "hud [on|off]", "show or hide the debug HUD", a -> { hud.setVisible(a.on(0, !hud.isVisible())); return null; });
```

The handler runs on the main thread, so it may touch anything. It returns its output (a String, or null), or a
`CompletableFuture<String>` when it ends later. `Args` reads words with errors that say what was wrong:
`str`, `num`, `on` (on/off), `pick` (an enum constant, listing the choices), `rest`. A thrown exception becomes
`error: <message>` plus the command's usage.

```java
commands.num("player.speed", "walking speed, m/s", () -> player.speed, v -> player.speed = (float) v);
commands.fields("render", renderer.settings());   // every public field, as render.<field>
commands.settings(settingsPage);                  // the saved settings
commands.topic("bake", "a line when the bricks are baked");   commands.emit("bake", "baked in 2.1 s");
```

`var`, `num`, `flag`, `choice` and `value` (read only) add one variable; a setter that throws refuses the value.
`emit` may be called from any thread. A command that holds something for an agent lets go in
`a.session().closing(...)`. The engine's commands are in `Game.addCommands`, the Builder's in
`BuilderMain.addCommands`. The console itself (`com.pick.console`) lives in
fidofetch/pick-shared, which both apps build from.
