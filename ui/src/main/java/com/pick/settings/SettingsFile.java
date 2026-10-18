package com.pick.settings;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

// An app's settings, kept between runs in one .properties file. Each module reads and writes only its own
// keys, through scope("sky"), which puts "sky." in front of them. A missing or unreadable file, or a value
// that doesn't parse, gives the module's own default. Saves go through a temp file, so a crash mid-save
// leaves the old file.
public final class SettingsFile {
    private final Path file;
    private final String header;
    private final Properties p = new Properties();

    private SettingsFile(Path file, String header) { this.file = file; this.header = header; }

    public static SettingsFile load(Path file, String header) {
        SettingsFile s = new SettingsFile(file, header);
        if (Files.exists(file)) {
            try (Reader r = Files.newBufferedReader(file)) { s.p.load(r); }
            catch (IOException | IllegalArgumentException e) {
                System.err.println("settings: can't read " + file + " (" + e.getMessage() + "), using the defaults");
                s.p.clear();
            }
        }
        return s;
    }

    public Path file() { return file; }
    public Scope scope(String prefix) { return new Scope(this, prefix.isEmpty() ? "" : prefix + "."); }

    // Every key and value, sorted (for the console's "get")
    public Map<String, String> all() {
        Map<String, String> m = new TreeMap<>();
        for (String k : p.stringPropertyNames()) m.put(k, p.getProperty(k));
        return m;
    }

    String raw(String key) { return p.getProperty(key); }
    void rawPut(String key, String v) { p.setProperty(key, v); }

    // The whole file as one string: a change to any value is a change to save
    public String snapshot() { return all().toString(); }

    public void save() {
        try {
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (Writer w = Files.newBufferedWriter(tmp)) { p.store(w, header); }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            System.err.println("settings: can't save " + file + ": " + e.getMessage());
        }
    }

    // One module's keys. f/i/b/str read with a default; put writes.
    public static final class Scope {
        private final SettingsFile f;
        private final String prefix;
        Scope(SettingsFile f, String prefix) { this.f = f; this.prefix = prefix; }

        public boolean has(String key) { return f.raw(prefix + key) != null; }
        public String str(String key, String def) { String v = f.raw(prefix + key); return v == null ? def : v.strip(); }
        public float f(String key, float def) {
            try { float v = Float.parseFloat(str(key, "")); return Float.isFinite(v) ? v : def; }
            catch (NumberFormatException e) { return def; }
        }
        public float f(String key, float def, float lo, float hi) { return Math.max(lo, Math.min(hi, f(key, def))); }
        public int i(String key, int def) {
            try { return Integer.parseInt(str(key, "")); } catch (NumberFormatException e) { return def; }
        }
        public boolean b(String key, boolean def) { String v = f.raw(prefix + key); return v == null ? def : Boolean.parseBoolean(v.strip()); }

        public void put(String key, float v) { f.rawPut(prefix + key, String.format(Locale.ROOT, "%s", v)); }
        public void put(String key, int v) { f.rawPut(prefix + key, Integer.toString(v)); }
        public void put(String key, boolean v) { f.rawPut(prefix + key, Boolean.toString(v)); }
        public void put(String key, String v) { f.rawPut(prefix + key, v); }
    }
}
