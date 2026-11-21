package com.pick.weather;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

// The Java port against the Island Weather Lab's own weather-grid.js (golden files made by the lab's golden.js:
// project files planning/weather/prototype/island/). Every field's sum must match the lab's to the last bit, step for
// step; a single cell off anywhere shows in its field's sum.
class WeatherGridTest {
    static byte[] res(String name) throws IOException {
        try (InputStream in = WeatherGridTest.class.getResourceAsStream("/weather/" + name)) {
            assertNotNull(in, name);
            return new GZIPInputStream(in).readAllBytes();
        }
    }
    static Island island() throws IOException { return Island.fromBytes(res("island-data.bin.gz")); }
    static WeatherGrid fresh() throws IOException { var w = new WeatherGrid(); w.load(island()); return w; }
    static WeatherGrid labStart() throws IOException {
        var w = fresh(); w.seed(7); w.setTime(200 * 86400 + 9 * 3600); w.sendLow(); return w;
    }
    static double sum(double[] a) { double s = 0; for (double v : a) s += v; return s; }

    // steps the grid along a golden file, failing at the first field that differs
    static void follow(WeatherGrid w, String file, double dt) throws IOException {
        String[] lines = new String(res(file), StandardCharsets.UTF_8).split("\n");
        String[] keys = lines[0].split(" ");
        for (int n = 0; n + 1 < lines.length; n++) {
            if (n > 0) w.step(dt);
            String[] r = lines[n + 1].split(" ");
            for (int f = 0; f < keys.length; f++) {
                double js = Double.parseDouble(r[f + 1]), java = sum(w.field(keys[f]));
                if (js != java) fail(file + " step " + n + ": " + keys[f] + " sums to " + java + ", the lab " + js);
            }
        }
    }

    @Test void matchesTheLabStepForStep10s() throws IOException { follow(labStart(), "run-10.txt.gz", 10); }

    @Test void matchesTheLabOverADayAt60s() throws IOException { follow(labStart(), "run-60.txt.gz", 60); }

    @Test void loadsTheLabsStateAndCarriesOnAsTheLabDoes() throws IOException {
        var w = fresh();
        w.loadState(res("state.bin.gz"));
        follow(w, "load.txt.gz", 10);
    }

    @Test void savesWhatItLoads() throws IOException {
        var w = fresh();
        byte[] lab = res("state.bin.gz");
        w.loadState(lab);
        var w2 = fresh();
        w2.loadState(w.saveState());
        for (String k : WeatherGrid.FIELDS) assertArrayEquals(w.field(k), w2.field(k), k);
        for (int i = 0; i < 30; i++) { w.step(10); w2.step(10); }
        for (String k : WeatherGrid.FIELDS) assertArrayEquals(w.field(k), w2.field(k), k + " after 30 steps");
        assertEquals(w.lows().size(), w2.lows().size());
        assertEquals(w.storms().size(), w2.storms().size());
    }

    @Test void sameSeedSameWeatherOtherSeedOther() throws IOException {
        var a = fresh(); a.seed(42); a.setTime(30 * 86400 + 13 * 3600);
        var b = fresh(); b.seed(42); b.setTime(30 * 86400 + 13 * 3600);
        var c = fresh(); c.seed(9); c.setTime(30 * 86400 + 13 * 3600);
        for (int i = 0; i < 120; i++) { a.step(30); b.step(30); c.step(30); }
        for (String k : WeatherGrid.FIELDS) assertArrayEquals(a.field(k), b.field(k), k);
        assertNotEquals(sum(a.field("vapour_mm")), sum(c.field("vapour_mm")));
    }

    @Test void catchUpOverALongGapRunsTheGroundThenFreshAir() throws IOException {
        var w = fresh(); w.seed(3); w.setTime(170 * 86400);
        var r = w.advanceTo(190 * 86400);
        assertEquals("fresh air", r.mode());
        assertEquals(190 * 86400, w.time(), 1e-6);
        for (double v : w.field("snow_mm")) assertTrue(Double.isFinite(v) && v >= 0);
        var s = w.advanceTo(190 * 86400 + 2 * 3600);
        assertEquals("stepped", s.mode());
        assertEquals(120, s.steps());
    }

    @Test void aHostClockSetsTheSunAndTheSeason() throws IOException {
        var w = fresh(); w.seed(5); w.setTime(10 * 86400);   // 00:00 by the grid's own clock
        w.hostClock(14, 0.55);                               // 14:00 in mid-July by the host's
        w.step(10);
        assertEquals(14, w.SUN.hour, 1e-9);
        assertTrue(w.SUN.heat > 0.5, "afternoon heat " + w.SUN.heat);
        assertEquals(0.55, w.SUN.yf, 1e-9);
        w.hostClock(Double.NaN, Double.NaN);
        w.step(10);
        assertEquals(((10 * 86400 + 10) / 3600.0) % 24, w.SUN.hour, 1e-6);   // the sun of the step's start
    }

    @Test void calendarSeasonsFromTheSeed() throws IOException {
        var w = fresh(); w.seed(7);
        double y = w.yearLen(0) / 86400;
        assertTrue(y >= 4 * 84 && y <= 4 * 98, "year " + y);
        var d = w.dateOf(w.timeOf(0.05, 0));   // ~19 January in the bake
        assertEquals(0, d.q());
        assertEquals(1, d.year());
    }

    // -Dweather.pkg=<World Package r12 folder>: the island read straight from the package is the lab's file. numpy
    // sums its block means in float32, Java in double, so a value on a rounding edge can land one step over (92 of
    // 1.7 M bytes on r12, all vegetation, one temperature and monthly values): every byte within one step, few off.
    @Test void fromPackageMatchesTheLabsFile() throws IOException {
        String pkg = System.getProperty("weather.pkg");
        Assumptions.assumeTrue(pkg != null && Files.isDirectory(Path.of(pkg)), "no -Dweather.pkg");
        byte[] mine = Island.fromPackage(Path.of(pkg), 0).toBytes(), lab = res("island-data.bin.gz");
        int diff = 0;
        for (int i = 0; i < lab.length; i++) {
            int d = (byte) (mine[i] - lab[i]);
            if (d != 0) diff++;
            assertTrue(Math.abs(d) <= 1, "byte " + i + ": " + (mine[i] & 255) + ", the lab " + (lab[i] & 255));
        }
        assertTrue(diff < lab.length / 1000, diff + " bytes differ");
    }
}
