# Worldseed design language

One page. Every screen in the engine, the launcher, the Builder and the Studio follows it. The values are in
`Theme`, `UiFonts` and `Motion` (module `ui`); a screen uses those names, never its own numbers. How to move,
size and check things in ImGui: the `game-ui-design` skill and world-builder `docs/ui-lessons.md`.

## Five principles

1. **Play is the product.** The shelf and the game come first. The Builder and the Studio are the workshop
   behind them: same colours and type, allowed to be dense and plain.
2. **The world is the interface.** In play nothing is on screen by default. The body, the tools and the world
   say what is going on. If something must show, it shows near what it is about, then goes.
3. **Dark and quiet; amber for the next step.** The frame is a dark walnut cabinet. One amber thing per
   screen: the step you most likely want next. Colour and light are for the planets.
4. **Things you make are the light.** Planets glow in their cases; maps are parchment. Nothing else is bright.
5. **Some things are meant to be found.** No menu, tip, tooltip, loading line or achievement names or hints
   at a hidden system. If you aren't sure whether something is one, ask before putting it on screen.

## Three places

| Place | What it is | Its look |
|---|---|---|
| **Play** (engine) | first person on a planet | no HUD by default; see "In play" |
| **The shelf** (launcher's first screen) | the planets you can play, in a dark cabinet | each planet floats in a small glass case lit from inside; the shelf fades to black at both ends; the chosen case is nearest |
| **The workshop** (Builder, Studio, launcher corner menu) | making planets and maps: long, for replays and modding | the panels of `Theme`, dense, one accent |

Making a planet is never the first thing offered. "New planet" is the empty case at the end of the shelf and
an entry in the corner menu.

## In play (a first proposal: what else shows is still being decided)

- **Default: nothing on screen.** No health bar, minimap or quest text.
- **Damage shows where it is:** on the tool (wear, chips, a bent edge) and on the body (limping, blur, the
  breath you hear). Repair and crafting are things you do with the object, not a menu.
- **Held things show what they show** while held: a compass points, a notebook has pages, a light lights.
- **A prompt, if any,** is one or two words at the thing (press E: "Pick up"), fades in over 0.15 s, out
  faster, and never stacks.
- **Pause (Esc)** is the one full menu: workshop look, over the frozen world dimmed to 40%.
- Debug panels (F3, the console) are tools for us and stay off unless asked for.

## Values (the tokens)

| Token | Value | Use |
|---|---|---|
| `BG` | #19130F at 93% | windows, the cabinet |
| `SURFACE`, `SURFACE2` | #261D16, #35291F | panels, fields and buttons |
| `BORDER` | #4A3A2A | separators only (quiet) |
| `EDGE` | #7A6249 | edges that mean something: fields, cases (3:1) |
| `TEXT`, `MUTED` | #ECE1CD, #A8977F | text 14:1; secondary text 6.5:1 |
| `ACCENT` | #E4A84B | the next step, focus, selection (8.8:1) |
| `WARN_TEXT` | #F07A62 | warnings and errors as words (6.7:1) |
| Black | #000000 | where the shelf fades to |

- **Type:** IBM Plex Sans. Body `UiFonts.BASE` (17, about 18 px of letter height) at 100%, headings ×1.25, the page question ×1.6. Text size goes to
  200%; every layout size is `UiFonts.px()`.
- **Space:** steps of 4: 4, 8, 12, 16, 24, 28 (page margin). Rounding 6 (fields), 10 (windows, cases).
- **Motion:** instant for keys and constant actions; 0.15 to 0.3 s for menus and fades; exits faster than
  entries; Reduce motion turns off slides and the planets' turning (`Motion.ambient()`).

## Components

| Component | Rule |
|---|---|
| **Main button** | `pushPrimary`, once per screen: the next step ("Play") |
| **Second button** | `pushOutlined`: easy to find, not the next step |
| **Plain button** | everything else |
| **Planet case** | a 10-rounded glass case with an `EDGE` frame, a thin highlight along its top, the planet lit inside, its name under it in `TEXT` |
| **Corner menu** | top right, a plain "≡" button: Build planets, Make maps, New planet, Options. Never the accent |
| **Status line** | `MUTED`, one line under what it is about; click for details (a log) |
| **Toast** | bottom centre, about 25 characters a second (3 s at least), never over the main button |
| **Feedback chip** | top centre on every screen (F9); hidden in play unless paused |

## Words

- Short, plain, sentence case. Verbs on buttons ("Play", "Open the Builder"), not nouns.
- Say what happens and why: a disabled control says why in its tooltip.
- Player words, not ours: "planet", not "World Package"; "Make maps", not "Studio" on the shelf.
- No exclamation marks, no "Successfully". A number has its unit.

## Before it ships

Check it at 1280×720, 1600×960 and 200% text; with no planets, one, and twenty; with a long name; while an
app is starting, running and failed. One amber thing on screen? Nothing names a hidden system? Pictures
through the console (`shot … send`), before and after.
