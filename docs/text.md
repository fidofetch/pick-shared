# Game text in files (`com.pick.text`)

Every line a player or tester reads lives in plain UTF-8 text files, one folder per language, so a rewrite is a file
edit (no rebuild) and a translation is one more folder. Plan: thread "Move all game text into outside documents"
(Pick 2026-11-19). The dev console, debug panels and logs stay English in code.

```
lang/
  en/            English, the reference: every key is here
    hud.txt      short lines, key = text
    units.txt    (in pick-shared's jar) how sizes read: 15 m (49 ft)
    pod/guide.txt  long-text files keep their own format (Text.open)
  de/            a translation: only the lines done so far, the rest fall back to English
```

## In code

```java
Text.get("hud.link.nosignal")                                   // link.nosignal = ... in lang/<code>/hud.txt
Text.get("hud.scan.toofar.sub", "dist", Units.distance(15))     // fills {dist}: "Within 15 m (49 ft)"
try (Reader r = Text.open("pod/guide.txt")) { ... }             // a long-text file, same fallback
```

A key's first part names its file. Files are looked for in the working folder's `lang/` first (the copy beside the
game, which Pick, testers and translators edit), then in the jars (`/lang/` on the class path). A key missing in the
language falls back to English; missing in English too it shows as `[?key]`.

## File format

```
# a note (whole line)
link.nosignal     = LINK NO SIGNAL
scan.toofar.sub   = Within {dist}
flakes            = {n|# flake|# flakes}
```

- `{name}` is a field the code fills in. Named, not numbered, so a translation can move it. `{{` `}}` for plain braces.
- `{n|one|other}` picks by count; `#` is the number. (English-style one/other only for now; languages with more plural
  forms will need more choices when one is added.)
- `\n` is a line break, `\\` a backslash. Spaces around `=` are dropped.
- Sizes never go in the files as numbers: the code fills a field with `Units.distance(m)`, `Units.length(m)`,
  `Units.mass(kg)`, which read `units.txt` (metric with imperial beside it).
- A live value in brackets, like the pod's `[SAFE]`, is written `[{status}]`: the brackets belong to the sentence, the
  word comes from its own key, so both get translated.
- Whole sentences, never pieces glued in code: `{item} is too heavy to keep in one hand`, not
  `describe(b) + " is too heavy..."`. Word order differs between languages.

## Languages and the test language

`Text.setLanguage("de")` switches; `Text.reload()` reads the files again. `xx` is the test language: every line comes
out accented and about 30% longer in `[ ]`, so plain English left on screen is a line still in code, and anything cut
off shows where a longer language will not fit.

## Checks (`TextCheck`, for an app's tests)

- `keysUsed(src)` + `missing(keys, lines)`: every `Text.get("...")` key has an English line.
- `fieldMismatches(english, other)`: a translation keeps the same `{fields}`.
- `glyphs(font)` + `notInFont(glyphs, lines)`: every character can be drawn by the font that shows it.
  Today: IBM Plex Sans has Latin, Cyrillic and Greek; the game's Atkinson Hyperlegible Next and VT323 have Latin only.
