package com.pick.text;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TextTest {
    @AfterEach void reset() { Text.clearPut(); Text.setLanguage("en"); Text.setFolder(Path.of("lang")); }

    @Test void parsesKeysNotesAndEscapes() {
        var m = Text.parse("﻿# a note\n\nlink.nosignal  =  LINK NO SIGNAL \ntwo = a\\nb\nbad line\nempty =\n");
        assertEquals(Map.of("link.nosignal", "LINK NO SIGNAL", "two", "a\nb", "empty", ""), m);
    }

    @Test void fillsFieldsAndCounts() {
        assertEquals("Within 15 m", Text.fill("Within {dist}", "dist", "15 m"));
        assertEquals("1 flake", Text.fill("{n|# flake|# flakes}", "n", 1));
        assertEquals("3 flakes", Text.fill("{n|# flake|# flakes}", "n", 3));
        assertEquals("left {as} is", Text.fill("left {as} is"));
        assertEquals("{plain}", Text.fill("{{plain}}"));
        assertEquals(List.of("dist", "n"), Text.fields("a {dist} b {n|x|y} {{no}}"));
    }

    @Test void fallsBackToEnglishThenShowsTheKey() {
        Text.put("en", "hud.a", "English a");
        Text.put("en", "hud.b", "English b");
        Text.put("de", "hud.a", "Deutsch a");
        Text.setLanguage("de");
        assertEquals("Deutsch a", Text.get("hud.a"));
        assertEquals("English b", Text.get("hud.b"));
        assertEquals("[?hud.none]", Text.get("hud.none"));
        assertEquals("[?nodot]", Text.get("nodot"));
    }

    @Test void testLanguageAccentsAndPadsButKeepsFields() {
        Text.put("en", "hud.a", "Scan {item} once");
        Text.setLanguage("xx");
        String s = Text.get("hud.a", "item", "rock");
        assertTrue(s.startsWith("[Sçåñ rock öñçé ~"), s);
        assertTrue(s.endsWith("~]"), s);
    }

    @Test void readsTheFolderBeforeTheJarAndReloads(@org.junit.jupiter.api.io.TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("en"));
        Files.writeString(dir.resolve("en/units.txt"), "m = {m} metres\n");
        Text.setFolder(dir);
        assertEquals("15 metres", Units.distance(15));
        Files.writeString(dir.resolve("en/units.txt"), "m = {m} M\n");
        assertEquals("15 metres", Units.distance(15));
        Text.reload();
        assertEquals("15 M", Units.distance(15));
        Files.createDirectories(dir.resolve("en/pod"));
        Files.writeString(dir.resolve("en/pod/guide.txt"), "== welcome\n");
        try (var r = Text.open("pod/guide.txt")) { assertEquals("== welcome\n", TextCheck.read(r)); }
    }

    @Test void unitsFromTheJar() {
        assertEquals("15 m (49 ft)", Units.distance(15));
        assertEquals("2.5 m (8.2 ft)", Units.distance(2.5));
        assertEquals("1 km (0.6 mi)", Units.distance(1000));
        assertEquals("55 cm (22 in)", Units.length(0.55));
        assertEquals("2.5 kg (5.5 lb)", Units.mass(2.5));
    }

    @Test void checksFindGaps() {
        Map<String, String> en = Map.of("hud.a", "x {dist}", "hud.b", "y");
        assertEquals(List.of("hud.c"), TextCheck.missing(new java.util.TreeSet<>(List.of("hud.a", "hud.c")), en));
        assertEquals(List.of("hud.a: English [dist] vs [d]"), TextCheck.fieldMismatches(en, Map.of("hud.a", "x {d}")));
    }

    @Test void readsAFontsCharacters() throws IOException {
        try (var in = TextTest.class.getResourceAsStream("/ui/fonts/IBMPlexSans-Variable.ttf")) {
            var g = TextCheck.glyphs(in);
            assertTrue(g.get('A') && g.get('é') && g.get('Ж'), "Latin and Cyrillic");
            assertFalse(g.get('中'), "no Chinese");
            assertEquals(List.of("U+4E2D 中 in hud.a"), TextCheck.notInFont(g, "A中中", "hud.a"));
        }
    }
}
