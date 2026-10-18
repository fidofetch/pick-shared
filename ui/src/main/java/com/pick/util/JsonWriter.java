package com.pick.util;

import java.util.List;
import java.util.Map;

// A small JSON writer for the app's own files (planet.json, presets, feedback notes): maps, lists, strings,
// numbers and booleans, indented so the files diff and read well. Parsing is Json.
public final class JsonWriter {
    private JsonWriter() {}

    public static String write(Object v) { StringBuilder b = new StringBuilder(); value(b, v, 0); return b.append('\n').toString(); }

    private static void value(StringBuilder b, Object v, int depth) {
        if (v == null) b.append("null");
        else if (v instanceof String s) string(b, s);
        else if (v instanceof Boolean || v instanceof Integer || v instanceof Long) b.append(v);
        else if (v instanceof Number n) b.append(number(n.doubleValue()));
        else if (v instanceof Map<?, ?> m) {
            if (m.isEmpty()) { b.append("{}"); return; }
            b.append("{\n");
            int i = 0;
            for (var e : m.entrySet()) {
                indent(b, depth + 1); string(b, String.valueOf(e.getKey())); b.append(": "); value(b, e.getValue(), depth + 1);
                b.append(++i < m.size() ? ",\n" : "\n");
            }
            indent(b, depth); b.append('}');
        } else if (v instanceof List<?> l) {
            if (l.isEmpty()) { b.append("[]"); return; }
            b.append("[\n");
            for (int i = 0; i < l.size(); i++) { indent(b, depth + 1); value(b, l.get(i), depth + 1); b.append(i + 1 < l.size() ? ",\n" : "\n"); }
            indent(b, depth); b.append(']');
        } else throw new IllegalArgumentException("can't write " + v.getClass());
    }

    // whole numbers without a trailing .0, others shortest round-trip
    public static String number(double d) {
        if (d == Math.rint(d) && Math.abs(d) < 1e15) return Long.toString((long) d);
        return Double.toString(d);
    }

    private static void indent(StringBuilder b, int n) { b.append("  ".repeat(n)); }

    private static void string(StringBuilder b, String s) {
        b.append('"');
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> { if (c < 0x20) b.append(String.format("\\u%04x", (int) c)); else b.append(c); }
            }
        }
        b.append('"');
    }
}
