# pick-shared

Code the engine ([voxel](https://github.com/fidofetch/voxel)) and World Seed
([world-builder](https://github.com/fidofetch/world-builder)) both use, kept in one place instead of copied.

| Module | What | Packages |
|---|---|---|
| `ui` | The look and the controls: colours and widget styles (`Theme`), widget helpers (`Ui`), the font and Text size (`UiFonts`, IBM Plex Sans), easing that honours Reduce motion (`Motion`), the Feedback panel (F9), a small JSON writer (for feedback notes), and the Settings page frame with its file (`SettingsPage`, `SettingsSection`, `SettingsFile`) | `com.pick.voxel.debug`, `com.pick.feedback`, `com.pick.util`, `com.pick.settings` |
| `console` | The command console (F1 or `) and the agent hook: commands, variables, watches and topics, the panel, the loopback server, `tools/console.py` | `com.pick.console` |

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

What stays with each app or module: its settings and its commands.

- **Settings**: a module adds a `SettingsSection` to the app's `SettingsPage`, the way it adds a panel to the
  debug HUD: a heading, its controls, and its own keys in the settings file under its prefix (`sky.haze`,
  `display.textScale`). The page draws the sections, saves the file half a second after a change, and the
  console's `set`/`get` reach every key. The engine's sections are in `voxel/ui/settings`; the Builder still has
  its own `AppSettings`.
- **Commands**: `Game.addCommands` (engine), `BuilderMain.addCommands` (Builder).

## Checks

```
./gradlew test
```

The font is SIL OFL (`ui/src/main/resources/ui/fonts/OFL-IBMPlexSans.txt`).
