package com.pick.launcher;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

// One thing the launcher opens: the engine, the Builder, or the Builder straight into the Studio. Each runs as its
// own program, through its repo's Gradle (so it is always the newest build), with its output in a log file.
//     app.start(projects, agentConsole, custom, extra)   from the launcher's main thread
//     app.state()                         closed, starting, running, failed
final class App {
    enum Id { ENGINE, BUILDER, STUDIO }
    enum State { CLOSED, RUNNING, FAILED }

    final Id id;
    final String title, blurb, button;
    private final Path logDir;
    private Process process;
    private long startedAt;
    private int lastExit;
    private String lastLine = "";
    private long lastLineRead;

    App(Id id, String title, String blurb, String button, Path logDir) {
        this.id = id; this.title = title; this.blurb = blurb; this.button = button; this.logDir = logDir;
    }

    // The command that starts it, from the folder that holds the repos (~/IdeaProjects). A line in the settings
    // ("engine.command = ...") replaces it; {projects} in it is that folder.
    static List<String> command(Id id, Path projects, boolean agentConsole, String custom, List<String> extra) {
        List<String> c = new ArrayList<>();
        if (custom != null && !custom.isBlank()) {
            for (String w : custom.trim().split("\\s+")) c.add(w.replace("{projects}", projects.toString()));
        } else {
            Path repo = projects.resolve(id == Id.ENGINE ? "voxel" : "world-builder");
            c.add(repo.resolve(windows() ? "gradlew.bat" : "gradlew").toString());
            c.add("-p"); c.add(repo.toString());
            c.add("--quiet");
            c.add(id == Id.ENGINE ? "run" : "runBuilder");
        }
        if (agentConsole) c.add(id == Id.ENGINE ? "-Dvoxel.console" : "-Dbuilder.console");
        if (id == Id.STUDIO) c.add("-Dbuilder.run=open baked; studio on");   // the newest baked planet, in the Studio
        if (extra != null) c.addAll(extra);   // which planet to play, or what to do first
        return c;
    }

    // The repo it needs, to say what is missing before trying
    static Path repo(Id id, Path projects) { return projects.resolve(id == Id.ENGINE ? "voxel" : "world-builder"); }

    void start(Path projects, boolean agentConsole, String custom, List<String> extra) throws IOException {
        if (isRunning()) return;
        if ((custom == null || custom.isBlank()) && !Files.isDirectory(repo(id, projects)))
            throw new IOException(repo(id, projects) + " is not there: set the projects folder below");
        Files.createDirectories(logDir);
        Path log = log();
        Files.writeString(log, "");
        var pb = new ProcessBuilder(command(id, projects, agentConsole, custom, extra));
        pb.directory((custom == null || custom.isBlank() ? repo(id, projects) : projects).toFile());
        pb.redirectErrorStream(true);
        pb.redirectOutput(log.toFile());
        if (System.getenv("JAVA_HOME") == null) pb.environment().put("JAVA_HOME", System.getProperty("java.home"));   // Gradle needs a JDK: this one
        process = pb.start();
        startedAt = System.currentTimeMillis();
        lastLine = "starting (building first)";
    }

    void stop() { if (process != null) process.toHandle().descendants().forEach(ProcessHandle::destroy); if (process != null) process.destroy(); }

    boolean isRunning() { return process != null && process.isAlive(); }

    State state() {
        if (isRunning()) return State.RUNNING;
        if (process != null) { lastExit = process.exitValue(); return lastExit == 0 || lastExit == 143 ? State.CLOSED : State.FAILED; }
        return State.CLOSED;
    }

    int exitCode() { return lastExit; }
    long runningSeconds() { return isRunning() ? (System.currentTimeMillis() - startedAt) / 1000 : 0; }
    Path log() { return logDir.resolve(id.name().toLowerCase() + ".log"); }

    // The newest line of its log (read a few times a second at most): what it is doing now
    String lastLine() {
        long now = System.currentTimeMillis();
        if (now - lastLineRead < 400) return lastLine;
        lastLineRead = now;
        String l = tail(log());
        if (l != null && !l.isBlank()) lastLine = l.length() > 140 ? l.substring(0, 140) + "..." : l;
        return lastLine;
    }

    // Why it stopped: the first error line of its log (not Gradle's "BUILD FAILED" at the end)
    String reason() {
        try {
            List<String> lines = Files.readAllLines(log(), StandardCharsets.UTF_8);
            for (String l : lines) {
                String t = l.strip(), lo = t.toLowerCase();
                if (t.isEmpty() || t.startsWith("*") || t.startsWith(">") || lo.contains("xdg_runtime_dir") || lo.startsWith("failure:") || lo.startsWith("build failed")) continue;
                if (lo.contains("exception") || lo.contains("error")) return t.length() > 160 ? t.substring(0, 160) + "..." : t;
            }
        } catch (IOException | RuntimeException ignored) { }
        return lastLine();
    }

    static String tail(Path file) {
        if (!Files.exists(file)) return null;
        try (var f = new RandomAccessFile(file.toFile(), "r")) {
            long n = f.length();
            int take = (int) Math.min(n, 4096);
            byte[] b = new byte[take];
            f.seek(n - take);
            f.readFully(b);
            String[] lines = new String(b, StandardCharsets.UTF_8).split("\r?\n");
            for (int i = lines.length - 1; i >= 0; i--) if (!lines[i].isBlank() && !lines[i].startsWith("Picked up JAVA_TOOL_OPTIONS")) return lines[i].strip();
            return null;
        } catch (IOException e) { return null; }
    }

    private static boolean windows() { return System.getProperty("os.name", "").toLowerCase().contains("win"); }
}
