package com.pick.util;
// Test only: the apps keep their own Json (the reader); the feedback test reads its notes back with this copy

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// A minimal JSON reader (world.json and other small manifests; the project has no JSON
// library, and world.json is small and machine-written). Objects become Map<String,Object>,
// arrays List<Object>, numbers Double, plus String, Boolean and null.
public final class Json {
    private final String s;
    private int i;

    private Json(String s) { this.s = s; }

    public static Object parse(String text) {
        Json j = new Json(text);
        Object v = j.value();
        j.ws();
        if (j.i != j.s.length()) throw j.error("trailing characters");
        return v;
    }

    private Object value() {
        ws();
        if (i >= s.length()) throw error("unexpected end");
        char c = s.charAt(i);
        return switch (c) {
            case '{' -> object();
            case '[' -> array();
            case '"' -> string();
            case 't' -> literal("true", Boolean.TRUE);
            case 'f' -> literal("false", Boolean.FALSE);
            case 'n' -> literal("null", null);
            default -> number();
        };
    }

    private Map<String, Object> object() {
        Map<String, Object> m = new LinkedHashMap<>();
        i++;
        ws();
        if (peek() == '}') { i++; return m; }
        while (true) {
            ws();
            String k = string();
            ws();
            expect(':');
            m.put(k, value());
            ws();
            if (peek() == ',') { i++; continue; }
            expect('}');
            return m;
        }
    }

    private List<Object> array() {
        List<Object> a = new ArrayList<>();
        i++;
        ws();
        if (peek() == ']') { i++; return a; }
        while (true) {
            a.add(value());
            ws();
            if (peek() == ',') { i++; continue; }
            expect(']');
            return a;
        }
    }

    private String string() {
        expect('"');
        StringBuilder b = new StringBuilder();
        while (true) {
            char c = s.charAt(i++);
            if (c == '"') return b.toString();
            if (c != '\\') { b.append(c); continue; }
            char e = s.charAt(i++);
            switch (e) {
                case 'n' -> b.append('\n');
                case 't' -> b.append('\t');
                case 'r' -> b.append('\r');
                case 'b' -> b.append('\b');
                case 'f' -> b.append('\f');
                case 'u' -> { b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; }
                default -> b.append(e);   // \" \\ \/
            }
        }
    }

    private Double number() {
        int start = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
        if (start == i) throw error("unexpected character '" + s.charAt(i) + "'");
        return Double.parseDouble(s.substring(start, i));
    }

    private Object literal(String word, Object v) {
        if (!s.startsWith(word, i)) throw error("expected " + word);
        i += word.length();
        return v;   // (NaN / Infinity aren't JSON; Python writes them only if asked to)
    }

    private void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }
    private char peek() { return i < s.length() ? s.charAt(i) : '\0'; }
    private void expect(char c) { if (peek() != c) throw error("expected '" + c + "'"); i++; }
    private RuntimeException error(String what) { return new IllegalArgumentException("world.json: " + what + " at " + i); }

    // typed access for the loader
    @SuppressWarnings("unchecked") public static Map<String, Object> obj(Object o, String key) { return (Map<String, Object>) ((Map<String, Object>) o).get(key); }
    @SuppressWarnings("unchecked") public static List<Object> list(Object o, String key) { return (List<Object>) ((Map<String, Object>) o).get(key); }
    @SuppressWarnings("unchecked") public static String str(Object o, String key) { return (String) ((Map<String, Object>) o).get(key); }
    @SuppressWarnings("unchecked") public static double num(Object o, String key, double dflt) {
        Object v = ((Map<String, Object>) o).get(key);
        return v instanceof Double d ? d : dflt;
    }
}
