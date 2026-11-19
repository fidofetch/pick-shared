package com.pick.text;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// The words the player reads, kept out of the code (docs/text.md): one folder per language, lang/<code>/, plain UTF-8
// text files you edit in any editor, so a rewrite needs no rebuild and a translation is one more folder.
//     Text.get("hud.link.nosignal")                       the line `link.nosignal = ...` in lang/<code>/hud.txt
//     Text.get("hud.scan.toofar.sub", "dist", Units.distance(15))   fills {dist}
//     Text.open("pod/guide.txt")                          a long-text file (its own format), with the same fallback
// A key's first part names its file. Each file is looked for in the working folder's lang/ first (the copy beside the
// game, which testers and translators edit), then in the jars (/lang/ on the class path). A line missing in the chosen
// language falls back to English; missing there too it shows as [?key], so a gap is easy to spot.
// File format:
//     # a note (whole line)
//     key = text                  spaces around = are dropped; \n is a line break, \\ a backslash
//     {name}                      a field the code fills in ({{ and }} for plain braces)
//     {n|one flake|# flakes}      a count: the first choice for 1, the second for the rest, # is the number
// The test language "xx" shows every line accented and about 30% longer, so any plain English left on screen is a
// line still in the code, and anything cut off shows where a longer language will not fit.
public final class Text {
    private Text() {}

    public static final String ENGLISH = "en", TEST = "xx";

    private static volatile String language = ENGLISH;
    private static volatile Path folder = Path.of("lang");
    private static final Map<String, Map<String, String>> files = new ConcurrentHashMap<>();   // "<lang>/<file>" -> lines
    private static final Map<String, Map<String, String>> extra = new ConcurrentHashMap<>();   // lines a test puts in

    /** The language in use (a lang/ folder name: en, de, fr, ... or xx, the test language). */
    public static String language() { return language; }

    /** Switches language; unknown codes fall back to English line by line. */
    public static void setLanguage(String code) {
        language = code == null || code.isBlank() ? ENGLISH : code.trim().toLowerCase(Locale.ROOT);
    }

    /** The folder looked in before the jars (lang/ in the working folder by default). */
    public static void setFolder(Path f) { folder = f; reload(); }
    public static Path folder() { return folder; }

    /** Reads the files again on the next look-up (console `lang reload`). */
    public static void reload() { files.clear(); }

    /** For tests: lines as if read from lang/<lang>/<file>.txt, over the files. */
    public static void put(String lang, String key, String text) {
        int dot = key.indexOf('.');
        extra.computeIfAbsent(lang + "/" + key.substring(0, dot), k -> new ConcurrentHashMap<>()).put(key.substring(dot + 1), text);
    }
    public static void clearPut() { extra.clear(); }

    /** The line for this key in the current language, its fields filled from name, value pairs. */
    public static String get(String key, Object... fields) {
        String t = raw(key);
        if (t == null) return "[?" + key + "]";
        if (language.equals(TEST)) t = pseudo(t);
        return fill(t, fields);
    }

    /** Whether the key has a line in English (the reference language). */
    public static boolean has(String key) { return lookup(ENGLISH, key) != null; }

    /** The line as written, fields unfilled: the current language, then English; null if neither has it. */
    public static String raw(String key) {
        String lang = language.equals(TEST) ? ENGLISH : language;
        String t = lookup(lang, key);
        if (t == null && !lang.equals(ENGLISH)) t = lookup(ENGLISH, key);
        return t;
    }

    private static String lookup(String lang, String key) {
        int dot = key.indexOf('.');
        if (dot <= 0) return null;
        String file = key.substring(0, dot), k = key.substring(dot + 1);
        Map<String, String> x = extra.get(lang + "/" + file);
        if (x != null && x.containsKey(k)) return x.get(k);
        return files.computeIfAbsent(lang + "/" + file, f -> load(f + ".txt")).get(k);
    }

    private static Map<String, String> load(String path) {
        try (Reader r = find(path)) {
            return r == null ? Map.of() : parse(r);
        } catch (IOException e) {
            System.err.println("[text] " + path + ": " + e.getMessage());
            return Map.of();
        }
    }

    /**
     * A long-text file (the pod guide, the prompt's pages) in the current language, else English: lang/<code>/<path>
     * in the working folder, then in the jars. Null if there is none.
     */
    public static Reader open(String path) throws IOException {
        String lang = language.equals(TEST) ? ENGLISH : language;
        Reader r = find(lang + "/" + path);
        if (r == null && !lang.equals(ENGLISH)) r = find(ENGLISH + "/" + path);
        return r;
    }

    private static Reader find(String path) throws IOException {
        Path p = folder.resolve(path);
        if (Files.isRegularFile(p)) return Files.newBufferedReader(p, StandardCharsets.UTF_8);
        InputStream in = Text.class.getResourceAsStream("/lang/" + path);
        return in == null ? null : new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
    }

    /** One file's lines, key -> text, in file order. Malformed lines are reported and skipped. */
    public static Map<String, String> parse(Reader in) throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        BufferedReader b = in instanceof BufferedReader br ? br : new BufferedReader(in);
        String line;
        int n = 0;
        while ((line = b.readLine()) != null) {
            n++;
            if (n == 1 && line.startsWith("﻿")) line = line.substring(1);   // a byte-order mark from a Windows editor
            String s = line.strip();
            if (s.isEmpty() || s.startsWith("#")) continue;
            int eq = s.indexOf('=');
            if (eq <= 0) { System.err.println("[text] line " + n + " has no key = text: " + s); continue; }
            out.put(s.substring(0, eq).strip(), unescape(s.substring(eq + 1).strip()));
        }
        return out;
    }

    public static Map<String, String> parse(String text) {
        try { return parse(new StringReader(text)); } catch (IOException e) { throw new IllegalStateException(e); }
    }

    private static String unescape(String s) {
        if (s.indexOf('\\') < 0) return s;
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char d = s.charAt(++i);
                b.append(d == 'n' ? '\n' : d == 't' ? '\t' : d);
            } else b.append(c);
        }
        return b.toString();
    }

    /** Fills {name} fields and {n|one|other} counts from name, value pairs; a field with no value stays as written. */
    public static String fill(String t, Object... fields) {
        if (t.indexOf('{') < 0 && t.indexOf('}') < 0) return t;
        StringBuilder b = new StringBuilder(t.length() + 16);
        int i = 0;
        while (i < t.length()) {
            char c = t.charAt(i);
            if (c == '{' && i + 1 < t.length() && t.charAt(i + 1) == '{') { b.append('{'); i += 2; continue; }
            if (c == '}' && i + 1 < t.length() && t.charAt(i + 1) == '}') { b.append('}'); i += 2; continue; }
            if (c == '{') {
                int end = t.indexOf('}', i);
                if (end < 0) { b.append(t, i, t.length()); break; }
                String body = t.substring(i + 1, end);
                b.append(field(body, fields, t.substring(i, end + 1)));
                i = end + 1;
                continue;
            }
            b.append(c);
            i++;
        }
        return b.toString();
    }

    private static String field(String body, Object[] fields, String asWritten) {
        int bar = body.indexOf('|');
        String name = (bar < 0 ? body : body.substring(0, bar)).strip();
        Object v = value(name, fields);
        if (v == null) return asWritten;
        if (bar < 0) return String.valueOf(v);
        String[] forms = body.substring(bar + 1).split("\\|", -1);
        double n = v instanceof Number num ? num.doubleValue() : Double.parseDouble(v.toString());
        String form = n == 1 || forms.length == 1 ? forms[0] : forms[1];
        return form.replace("#", v instanceof Double || v instanceof Float ? Units.num(n) : String.valueOf(v));
    }

    private static Object value(String name, Object[] fields) {
        for (int i = 0; i + 1 < fields.length; i += 2) if (name.equals(fields[i])) return fields[i + 1];
        return null;
    }

    /** The names of a line's fields ({dist}, {n|...} -> dist, n), in order, for checking a translation against English. */
    public static List<String> fields(String t) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if ((c == '{' || c == '}') && i + 1 < t.length() && t.charAt(i + 1) == c) { i++; continue; }
            if (c != '{') continue;
            int end = t.indexOf('}', i);
            if (end < 0) break;
            String body = t.substring(i + 1, end);
            int bar = body.indexOf('|');
            out.add((bar < 0 ? body : body.substring(0, bar)).strip());
            i = end;
        }
        return out;
    }

    // The test language: letters swapped for accented ones (all in Latin-1, which every font we ship has), the line
    // padded about 30%, in [ ] so a clipped end shows. Fields are left alone so the code still fills them.
    private static final String PLAIN = "aceinouyACEINOUY", ACCENT = "åçéîñöûýÅÇÉÎÑÖÛÝ";

    public static String pseudo(String t) {
        StringBuilder b = new StringBuilder(t.length() * 2);
        int letters = 0, depth = 0;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '{') depth++;
            if (depth == 0) {
                int k = PLAIN.indexOf(c);
                b.append(k >= 0 ? ACCENT.charAt(k) : c);
                if (Character.isLetter(c)) letters++;
            } else b.append(c);
            if (c == '}' && depth > 0) depth--;
        }
        return "[" + b + " " + "~".repeat(Math.max(1, (int) Math.ceil(letters * 0.3))) + "]";
    }
}
