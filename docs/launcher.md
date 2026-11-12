# The launcher (the shelf)

The first screen of Worldseed (look and rules: [design-language.md](design-language.md)). It opens on **the
shelf**: the planets you can play, each floating in a small glass case in a dark cabinet that fades to black
at both ends. Choose one (click, arrows, mouse wheel) and **Play** opens it in the engine
(`voxel/gradlew run -Dvoxel.world=<its package>`). The empty case at the end is **New planet** (the Builder with
a new planet: a long project, so it is never the first thing offered).

**Continue and New game.** When the chosen planet has a save (the engine's `saves/world-<name>/continue.save`
beside the voxel repo), the main button is **Continue** (the engine with `-Dvoxel.continue`) and a quieter **New
game** sits under it (the engine starts fresh; the old save moves to its `older/` folder on the first save), with
when it was saved. Without a save the button is **Play**.

**Prepare this world (World disk size, Pick 2026-11-11).** The first Play of a planet asks how much of it to bake
ahead and keep on disk, so play is smoother and later starts are quick: 2 GB, 8 GB (suggested), 16 GB, or Bake as
I play. A one-time cost, like compiling shaders: play starts after the usual loading screen and the rest carries
on in the background. The choice is kept per planet (`prepare.<name>` in the settings file; Menu, World disk size
changes it) and every start passes it as `-Dvoxel.preloadGB=<GB>` (voxel `world/brick/Preloader`, which fills 75%
of it and leaves the rest for ground walked to); the first start after choosing also passes `-Dvoxel.worldDiskGB`
(the engine's Settings > View "Disk space for the world"). Options too big for the free space are greyed out.
Plan and measurements: the project's `planning/world-preload/`.

The workshop is in the corner **Menu**: Build planets (the Builder), Make maps (the Builder, straight into the
Studio with the newest baked planet: `-Dbuilder.run="open baked; studio on"`), New planet, Options.

Each app runs as its own program, built from its repo first (so it is always the newest code), and keeps running
when the launcher closes. Under Play a line says what it is doing; if it stopped with an error, the line says
the first error in its log (click it for the whole log, `~/.cache/pick-launcher/<app>.log`).

## Where the planets come from

Every baked World Package (a folder with `world.json`) in: `<projects>/world-builder/worlds/*/bake/world` (the
Builder run from its repo), `~/WorldSeed/worlds/*/bake/world` (installed), `<projects>/world-packages/*`, and any
folders in Options' "Planets also from" (separated by `;`). Newest first; looked for again every few seconds.
The globes are drawn from each package's own heights, ice, lakes and plant cover.

## Starting it

```
~/IdeaProjects/pick-shared/tools/launcher            # or: ./gradlew :launcher:run
~/IdeaProjects/pick-shared/tools/launcher --desktop  # adds "Worldseed" to the app menu (Linux)
```

`tools/launcher` finds a JDK when `JAVA_HOME` is not set (the newest in `~/.jdks`).

## Options (Menu, Options...)

- **Projects folder**: the folder that holds `voxel`, `world-builder` and `pick-shared` (`~/IdeaProjects`).
- **Planets also from**: more folders of planets.
- **Reduce motion**: the shelf jumps instead of sliding, and the planets stop turning.
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
| `planets`, `pick <n\|name\|new>`, `play` | the shelf: list, choose a case, Play (Continue when it has a save) |
| `newgame` | the chosen planet from the start |
| `prepare [2\|8\|16\|play]` | the chosen planet's World disk size (no value: open the choice) |
| `open <engine\|builder\|studio>`, `stop <...>` | open (engine: the chosen planet) or close an app |
| `menu`, `options` | open the corner menu, Options |
| `apps` | each app's state and newest log line |
| `text <100..200>`, `window <w> <h>` | Text size, window size |
| `shot [file] [send]`, `film ...` | pictures (relative files go to `~/.cache/pick-launcher/shots`) |
| `quit` | close the launcher (the apps stay open) |

Variables: `projects`, `moreShelves`, `agentConsole`, `planet` (the chosen case), `prepared` (its disk size), `app.engine`, `app.builder`,
`app.studio` (read only).
