package com.pick.text;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

// pick-shared's own lang/en files (ui: units.txt, shared.txt; launcher: launcher.txt) against the code that asks for
// them, and against the font that draws them (IBM Plex Sans). Tests run in ui/, so the launcher is ../launcher.
class SharedTextFilesTest {
    static final Path UI = Path.of("src/main"), LAUNCHER = Path.of("../launcher/src/main");

    @Test void everyKeyHasAnEnglishLine() throws IOException {
        Map<String, String> en = new TreeMap<>(TextCheck.lines(UI.resolve("resources/lang/en")));
        en.putAll(TextCheck.lines(LAUNCHER.resolve("resources/lang/en")));
        var keys = new TreeSet<>(TextCheck.keysUsed(UI.resolve("java"), LAUNCHER.resolve("java")));
        // the short helpers: t("...") in the launcher is launcher.*, in the F9 panel shared.feedback.*
        helper(LAUNCHER.resolve("java/com/pick/launcher/Launcher.java"), "launcher.", keys);
        helper(UI.resolve("java/com/pick/feedback/FeedbackPanel.java"), "shared.feedback.", keys);
        keys.removeIf(k -> k.endsWith("."));   // prefixes a number is added to: those follow
        for (int i = 0; i < 4; i++) {
            keys.add("shared.feedback.category." + i);
            keys.add("shared.text_size." + i);
            for (String part : List.of("name", "time", "what")) keys.add("launcher.prepare." + i + "." + part);
        }
        assertTrue(keys.size() > 50, "the scan found the keys");
        assertEquals(List.of(), TextCheck.missing(keys, en), "keys with no English line");
    }

    @Test void everyCharacterIsInPlex() throws IOException {
        java.util.BitSet plex;
        try (var in = getClass().getResourceAsStream("/ui/fonts/IBMPlexSans-Variable.ttf")) { plex = TextCheck.glyphs(in); }
        Map<String, String> en = new TreeMap<>(TextCheck.lines(UI.resolve("resources/lang/en")));
        en.putAll(TextCheck.lines(LAUNCHER.resolve("resources/lang/en")));
        assertEquals(List.of(), TextCheck.notInFont(plex, en));
    }

    @Test void feedbackCategoriesMatchTheNotes() {
        assertEquals(com.pick.feedback.FeedbackNotes.CATEGORIES.size(), 4, "shared.feedback.category.0-3 name them");
    }

    static void helper(Path file, String prefix, java.util.Set<String> keys) throws IOException {
        var m = Pattern.compile("\\bt\\(\\s*\"([A-Za-z0-9_.\\-]+)\"").matcher(Files.readString(file));
        while (m.find()) keys.add(prefix + m.group(1));
        // keys chosen by a ternary: t(cond ? "a" : "b")
        var tern = Pattern.compile("\\bt\\([^;]*?\\?\\s*\"([a-z0-9_.]+)\"\\s*:\\s*\"([a-z0-9_.]+)\"").matcher(Files.readString(file));
        while (tern.find()) { keys.add(prefix + tern.group(1)); keys.add(prefix + tern.group(2)); }
    }
}
