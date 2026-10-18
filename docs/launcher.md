# The launcher (start page)

One window that opens the three parts of Worldseed:

| Card | Opens | How |
|---|---|---|
| **Play** | the engine (voxel) | `voxel/gradlew run` |
| **Build planets** | the Builder (world-builder) | `world-builder/gradlew runBuilder` |
| **Make maps** | the Builder, straight into the Studio with the newest baked planet | `runBuilder -Dbuilder.run="open baked; studio on"` |

Each app runs as its own program, built from its repo first (so it is always the newest code), and keeps running
when the launcher closes. The app opened last has the accent button; a running app's button is **Stop**. The line
under each button says what it is doing (its newest log line); click it to open the whole log
(`~/.cache/pick-launcher/<app>.log`).

## Starting it

```
~/IdeaProjects/pick-shared/tools/launcher            # or: ./gradlew :launcher:run
~/IdeaProjects/pick-shared/tools/launcher --desktop  # adds "Worldseed" to the app menu (Linux)
```

`tools/launcher` finds a JDK when `JAVA_HOME` is not set (the newest in `~/.jdks`).

## Options (under the cards)

- **Projects folder**: the folder that holds `voxel`, `world-builder` and `pick-shared` (`~/IdeaProjects`).
- **Agent console**: open the apps with their console port (engine 47100, Builder 47101), so an agent can drive
  them with `tools/console.py`.
- **Text size**: this page only; each app has its own in its Settings.

They are kept in `~/.config/pick-launcher/launcher.properties` (another file: `-Dlauncher.settings=...`). A line
there replaces an app's command, with `{projects}` for the folder:

```
engine.command = {projects}/voxel/gradlew -p {projects}/voxel run --offline
```

## For agents

`-Dlauncher.console` opens its console on port 47102; `-Dlauncher.run="..."` runs commands at start.

```
python3 tools/console.py --port 47102 "apps; open builder"
python3 tools/console.py --port 47102 "shot home.png send"
```

| Command | Does |
|---|---|
| `open <engine\|builder\|studio>`, `stop <...>` | a card's button |
| `apps` | each app's state and newest log line |
| `text <100..200>`, `window <w> <h>` | Text size, window size |
| `shot [file] [send]`, `film ...` | pictures (relative files go to `~/.cache/pick-launcher/shots`) |
| `quit` | close the launcher (the apps stay open) |

Variables: `projects`, `agentConsole`, `app.engine`, `app.builder`, `app.studio` (read only).
