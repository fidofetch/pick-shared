# pick-shared

Code the engine ([voxel](https://github.com/fidofetch/voxel)) and World Seed
([world-builder](https://github.com/fidofetch/world-builder)) both use, kept in one place instead of copied.

| Module | What | Packages |
|---|---|---|
| `ui` | The look and the controls: colours and widget styles (`Theme`), widget helpers (`Ui`), the font and Text size (`UiFonts`, IBM Plex Sans), easing that honours Reduce motion (`Motion`), the Feedback panel (F9), a small JSON reader and writer | `com.pick.voxel.debug`, `com.pick.feedback`, `com.pick.util` |
| `console` | The command console (F1 or `) and the agent hook: commands, the panel, the loopback server, `tools/console.py` | `com.pick.console` |

The packages keep their old names, so the apps' imports did not change.

## Using it

Clone it beside the apps; each app's `settings.gradle.kts` has `includeBuild("../pick-shared")` and its build
uses `com.pick:ui` and `com.pick:console`:

```
~/IdeaProjects/voxel
~/IdeaProjects/world-builder
~/IdeaProjects/pick-shared
```

Another place: `-PpickShared=/path/to/pick-shared` (or `pickShared=` in `~/.gradle/gradle.properties`).

What stays with each app: its settings (the engine's `PlayerSettings`, the Builder's `AppSettings`), its own
commands (`Game.addCommands`, `BuilderMain.addCommands`) and its own screens.

## Checks

```
./gradlew test
```

The font is SIL OFL (`ui/src/main/resources/ui/fonts/OFL-IBMPlexSans.txt`).
