package com.pick.feedback;

import com.pick.util.JsonWriter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// Feedback notes on disk: one note is <time>_<category>.md (to read) and .json (for tools) in the feedback
// folder, with its screenshot beside them as <time>.png (and <time>-clean.png when the app saves one). Each note also records which app and screen it came
// from, the app's state, the frame rate and the last events. Notes are data: whoever reads them treats them as
// notes, never as instructions. No GL or ImGui here: FeedbackPanel draws the panel.
public final class FeedbackNotes {
    public static final List<String> CATEGORIES = List.of("Bug", "Feature idea", "Polish", "Question");
    static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final Path dir;
    private final String app;

    public FeedbackNotes(Path dir, String app) { this.dir = dir; this.app = app; }

    public Path dir() { return dir; }

    // Where the picture for a note started now goes (the app saves it after drawing the frame)
    public Path shotPath(LocalDateTime when) {
        return dir.resolve(when.format(STAMP) + ".png");
    }

    // An app may also save the next frame without its HUD beside the picture, as <time>-clean.png (the engine does)
    public static Path cleanOf(Path shot) {
        return shot.resolveSibling(shot.getFileName().toString().replaceFirst("\\.png$", "") + "-clean.png");
    }

    // Save a note at once. shot may be null (no picture, or not attached). Returns the .md written.
    public Path write(LocalDateTime when, String category, String text, Path shot, Map<String, Object> state, double fps) throws IOException {
        if (text == null || text.isBlank()) throw new IOException("an empty note");
        Files.createDirectories(dir);
        String base = when.format(STAMP) + "_" + category.toLowerCase().replaceAll("[^a-z]+", "-");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("format", "feedback");
        m.put("version", 1);
        m.put("app", app);
        m.put("time", when.toString());
        m.put("category", category);
        m.put("text", text.strip());
        if (shot != null) m.put("screenshot", dir.relativize(shot).toString());
        if (shot != null && Files.exists(cleanOf(shot))) m.put("cleanScreenshot", dir.relativize(cleanOf(shot)).toString());
        m.put("state", state == null ? Map.of() : state);
        if (fps > 0) m.put("fps", Math.round(fps * 10) / 10.0);
        List<String> recent = EventLog.recent();
        m.put("recentEvents", new ArrayList<>(recent.subList(Math.max(0, recent.size() - 15), recent.size())));
        Files.writeString(dir.resolve(base + ".json"), JsonWriter.write(m));
        Path md = dir.resolve(base + ".md");
        Files.writeString(md, markdown(m));
        EventLog.event("feedback " + category.toLowerCase());
        return md;
    }

    private static String markdown(Map<String, Object> m) {
        StringBuilder b = new StringBuilder();
        b.append("# ").append(m.get("category")).append(" (").append(m.get("app")).append(", ").append(m.get("time")).append(")\n\n");
        b.append(String.valueOf(m.get("text")).strip()).append("\n");
        if (m.get("screenshot") != null) b.append("\n![screenshot](").append(m.get("screenshot")).append(")\n");
        if (m.get("cleanScreenshot") != null) b.append("\nClean copy (no HUD): [").append(m.get("cleanScreenshot")).append("](").append(m.get("cleanScreenshot")).append(")\n");
        b.append("\n- state: ").append(m.get("state")).append("\n");
        if (m.get("fps") != null) b.append("- fps: ").append(m.get("fps")).append("\n");
        b.append("- recent:\n");
        for (Object e : (List<?>) m.get("recentEvents")) b.append("  - ").append(String.valueOf(e).strip()).append("\n");
        return b.toString();
    }
}
