package com.pick.text;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

// The checks an app's tests run over its lang/ folder and its code (docs/text.md):
//     keysUsed(src)                 every Text.get("...") key in the code
//     missing(keys, lang)           keys the code uses that English has no line for
//     fieldMismatches(lang, other)  lines whose {fields} differ from English (a translation that drops or renames one)
//     glyphs(font)                  the characters a font file can draw, to check a language's files against
public final class TextCheck {
    private TextCheck() {}

    private static final Pattern GET = Pattern.compile("Text\\.(?:get|raw|has)\\(\\s*\"([A-Za-z0-9_.\\-]+)\"");

    /** The keys passed as plain strings to Text.get / raw / has in the .java files under these folders. */
    public static Set<String> keysUsed(Path... roots) throws IOException {
        Set<String> out = new TreeSet<>();
        for (Path root : roots) {
            if (!Files.isDirectory(root)) continue;
            try (Stream<Path> s = Files.walk(root)) {
                for (Path p : (Iterable<Path>) s.filter(f -> f.toString().endsWith(".java"))::iterator) {
                    // comment lines left out (an example in a doc comment is not a key the code asks for)
                    String code = Files.readString(p).replaceAll("(?m)^\\s*(//|\\*|/\\*).*$", "");
                    Matcher m = GET.matcher(code);
                    while (m.find()) out.add(m.group(1));
                }
            }
        }
        return out;
    }

    /** Every key a language folder holds (file.key), from its short-text .txt files (not the long-text subfolders). */
    public static Map<String, String> lines(Path langFolder) throws IOException {
        Map<String, String> out = new java.util.TreeMap<>();
        if (!Files.isDirectory(langFolder)) return out;
        try (Stream<Path> s = Files.list(langFolder)) {
            for (Path p : (Iterable<Path>) s.filter(f -> f.toString().endsWith(".txt")).sorted()::iterator) {
                String file = p.getFileName().toString().replaceFirst("\\.txt$", "");
                for (var e : Text.parse(Files.readString(p, StandardCharsets.UTF_8)).entrySet()) out.put(file + "." + e.getKey(), e.getValue());
            }
        }
        return out;
    }

    /** Keys used that have no line in this set of lines. */
    public static List<String> missing(Set<String> keys, Map<String, String> lines) {
        List<String> out = new ArrayList<>();
        for (String k : keys) if (!lines.containsKey(k)) out.add(k);
        return out;
    }

    /** "key: en {a, b} vs {a}" for each line of other whose fields differ from English, or that English lacks. */
    public static List<String> fieldMismatches(Map<String, String> english, Map<String, String> other) {
        List<String> out = new ArrayList<>();
        for (var e : other.entrySet()) {
            String en = english.get(e.getKey());
            if (en == null) { out.add(e.getKey() + ": not in English"); continue; }
            var a = new TreeSet<>(Text.fields(en)); var b = new TreeSet<>(Text.fields(e.getValue()));
            if (!a.equals(b)) out.add(e.getKey() + ": English " + a + " vs " + b);
        }
        return out;
    }

    /** "U+0416 Ж in key" for each character in the lines (and long-text files) that the font cannot draw. */
    public static List<String> notInFont(BitSet font, Map<String, String> lines) {
        List<String> out = new ArrayList<>();
        for (var e : lines.entrySet()) out.addAll(notInFont(font, e.getValue(), e.getKey()));
        return out;
    }

    public static List<String> notInFont(BitSet font, String text, String where) {
        List<String> out = new ArrayList<>();
        Set<Integer> seen = new TreeSet<>();
        text.codePoints().forEach(cp -> {
            if (cp < 0x20 || font.get(cp) || !seen.add(cp)) return;
            out.add(String.format("U+%04X %s in %s", cp, new String(Character.toChars(cp)), where));
        });
        return out;
    }

    /** The characters a TrueType / OpenType font maps (its cmap, formats 4 and 12). */
    public static BitSet glyphs(InputStream in) throws IOException {
        ByteBuffer b = ByteBuffer.wrap(in.readAllBytes());
        int tables = b.getShort(4) & 0xffff, cmap = -1;
        for (int i = 0; i < tables; i++) {
            int rec = 12 + 16 * i;
            String tag = new String(new byte[]{b.get(rec), b.get(rec + 1), b.get(rec + 2), b.get(rec + 3)}, StandardCharsets.US_ASCII);
            if (tag.equals("cmap")) cmap = b.getInt(rec + 8);
        }
        if (cmap < 0) throw new IOException("no cmap table");
        BitSet out = new BitSet(0x10000);
        int n = b.getShort(cmap + 2) & 0xffff;
        for (int i = 0; i < n; i++) {
            int platform = b.getShort(cmap + 4 + 8 * i) & 0xffff, enc = b.getShort(cmap + 6 + 8 * i) & 0xffff;
            if (!(platform == 0 || platform == 3 && (enc == 1 || enc == 10))) continue;   // Unicode subtables only
            int sub = cmap + b.getInt(cmap + 8 + 8 * i), format = b.getShort(sub) & 0xffff;
            if (format == 4) {
                int segs = (b.getShort(sub + 6) & 0xffff) / 2;
                int ends = sub + 14, starts = ends + 2 * segs + 2, deltas = starts + 2 * segs, ranges = deltas + 2 * segs;
                for (int s = 0; s < segs; s++) {
                    int end = b.getShort(ends + 2 * s) & 0xffff, start = b.getShort(starts + 2 * s) & 0xffff;
                    int delta = b.getShort(deltas + 2 * s), range = b.getShort(ranges + 2 * s) & 0xffff;
                    for (int c = start; c <= end && c != 0xffff; c++) {
                        int g = range == 0 ? (c + delta) & 0xffff
                                : b.getShort(ranges + 2 * s + range + 2 * (c - start)) & 0xffff;
                        if (range != 0 && g != 0) g = (g + delta) & 0xffff;
                        if (g != 0) out.set(c);
                    }
                }
            } else if (format == 12) {
                int groups = b.getInt(sub + 12);
                for (int g = 0; g < groups; g++) {
                    int start = b.getInt(sub + 16 + 12 * g), end = b.getInt(sub + 20 + 12 * g);
                    out.set(start, end + 1);
                }
            }
        }
        return out;
    }

    public static BitSet glyphs(Path font) throws IOException {
        try (InputStream in = Files.newInputStream(font)) { return glyphs(in); }
    }

    /** A whole long-text file as one string (for notInFont). */
    public static String read(Reader r) throws IOException {
        StringBuilder b = new StringBuilder();
        char[] buf = new char[8192];
        for (int n; (n = r.read(buf)) > 0; ) b.append(buf, 0, n);
        return b.toString();
    }
}
