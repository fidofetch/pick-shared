package com.pick.feedback;

import com.pick.util.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

// A feedback note is saved at once as .md and .json, with its picture, the state and the last events
class FeedbackNotesTest {
    @Test void aNoteIsSavedWithItsStateAndEvents(@TempDir Path dir) throws IOException {
        FeedbackNotes notes = new FeedbackNotes(dir.resolve("feedback"), "builder");
        LocalDateTime when = LocalDateTime.of(2026, 10, 18, 11, 30, 5);
        Path shot = notes.shotPath(when);
        Files.createDirectories(shot.getParent());
        Files.write(shot, new byte[]{1, 2, 3});
        EventLog.event("planet.open orrin");
        Path md = notes.write(when, "Feature idea", "  100% of the sliders need words  ", shot, Map.of("screen", "editor"), 59.94);

        assertEquals("20261018-113005_feature-idea.md", md.getFileName().toString());
        Object root = Json.parse(Files.readString(md.resolveSibling("20261018-113005_feature-idea.json")));
        assertEquals("feedback", Json.str(root, "format"));
        assertEquals("builder", Json.str(root, "app"));
        assertEquals("100% of the sliders need words", Json.str(root, "text"));
        assertEquals("20261018-113005.png", Json.str(root, "screenshot"));
        assertEquals("editor", Json.str(Json.obj(root, "state"), "screen"));
        assertEquals(59.9, Json.num(root, "fps", 0), 1e-9);
        List<Object> recent = Json.list(root, "recentEvents");
        assertTrue(String.valueOf(recent.get(recent.size() - 1)).endsWith("planet.open orrin"));

        String text = Files.readString(md);
        assertTrue(text.startsWith("# Feature idea (builder, "));
        assertTrue(text.contains("![screenshot](20261018-113005.png)"));
    }

    @Test void noPictureAndNoEmptyNotes(@TempDir Path dir) throws IOException {
        FeedbackNotes notes = new FeedbackNotes(dir, "engine");
        LocalDateTime when = LocalDateTime.now();
        assertThrows(IOException.class, () -> notes.write(when, "Bug", "   ", null, Map.of(), 0));
        Path md = notes.write(when, "Bug", "the toolbar covers the map", null, null, 0);
        assertFalse(Files.readString(md).contains("screenshot"));
        try (Stream<Path> s = Files.list(dir)) { assertEquals(2, s.count()); }   // .md and .json, nothing else
    }
}
