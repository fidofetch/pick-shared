# weather: the island weather grid

`com.pick.weather.WeatherGrid` is the Island Weather Lab's module (`weather-grid.js` 0.4.0, project files
`planning/weather/prototype/island/`, plan `planning/weather/planet-weather-grid.md` section 4.6) ported line for
line to Java. It is a 2D weather grid over the demo island (package face 0, 128 x 128 cells of ~135 m): vapour,
layered and heaped cloud, rain and snow, lying snow and soil water, pressure lows and highs with their fronts, the
sun's heat low and sea breezes, afternoon storms and their gust fronts, cap and banner clouds, lee waves and
lenticulars, fog and dust. It holds climate and weather only: lightning, wet ground looks and haze stay with the app.

- **Input**: `Island`, from the lab's `island-data.bin` (`Island.fromBytes`) or straight from a World Package folder
  (`Island.fromPackage(dir, 0)`: block means of `height_m`, `precip_mm`, `vegetation`, `temp_mean_c` and the twelve
  months of `wind_ms`, `precip_share`, `temp_season_c`, `cloud_cover`, `cloud_convective`, `snow_mm`).
- **Use**: `load(island)`, `seed(n)`, `setTime(t)` (seconds from the start of year 0, season 0 = the bake's 1
  December), `step(dt)` (10 s in the lab), then read `field(key)`: the 24 live channels in `FIELDS` (`rain_mm_h`,
  `cover_layered`, `cover_heaped`, `wind_low_e`, `wind_low_s`, `pressure_hpa`, `pressure_change_3h_hpa`, ...; grid x
  east, y south). The arrays are live; a host copies what another thread reads.
- **Host clock**: `hostClock(hour, yearFrac)` takes the sun's hour and the year from the host (the engine: the air on
  real time, the day and the seasons on the game's clock; Pick 2026-11-21). NaN gives the grid's own clock back.
- **Save, load, catch up**: `saveState()` / `loadState(bytes)` (~1.1 MB, the lab's format: a state from either loads
  in the other), `advanceTo(t)` (up to 6 h stepped at 60 s; longer gaps run the ground day by day, then fresh air).

## Checks

`./gradlew :weather:test` runs the port against golden files from the lab's own `weather-grid.js` (made with
`golden.js` beside it): every field's sum, step for step, must match to the last bit, at 10 s steps for an hour, at
60 s for a day, and after loading the lab's saved state. Add `-Dweather.pkg=<World Package r12 folder>` to check
`Island.fromPackage` against the lab's file too.

How it stays bit for bit: the maths keeps the lab's order of operations in doubles, grids hold doubles rounded to
float where the lab has a Float32Array, `StrictMath` stands in for the browser's `Math` (both fdlibm), and `hypot`
and the integer hashes are done as V8 does them. A change to the model goes into the lab first, then here, then new
golden files.
