package com.pick.weather;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// Just enough JSON for world.json and the weather state's head, so the module needs no library: objects (keys in
// order), arrays, strings, numbers (as Double), true / false / null. write() prints numbers so they read back exactly,
// and non-finite numbers as null, as JavaScript's JSON.stringify does.
final class MiniJson {
    private final String s;
    private int p;

    private MiniJson(String s) { this.s = s; }

    static Object parse(String s) {
        var j = new MiniJson(s);
        Object v = j.value();
        j.ws();
        if (j.p != s.length()) throw new IllegalArgumentException("json: trailing text at " + j.p);
        return v;
    }

    private void ws() { while (p < s.length() && Character.isWhitespace(s.charAt(p))) p++; }

    private Object value() {
        ws();
        char c = s.charAt(p);
        switch (c) {
            case '{': {
                p++;
                var m = new LinkedHashMap<String, Object>();
                ws();
                if (s.charAt(p) == '}') { p++; return m; }
                while (true) {
                    ws();
                    String k = string();
                    ws();
                    expect(':');
                    m.put(k, value());
                    ws();
                    if (s.charAt(p) == ',') { p++; continue; }
                    expect('}');
                    return m;
                }
            }
            case '[': {
                p++;
                var a = new ArrayList<Object>();
                ws();
                if (s.charAt(p) == ']') { p++; return a; }
                while (true) {
                    a.add(value());
                    ws();
                    if (s.charAt(p) == ',') { p++; continue; }
                    expect(']');
                    return a;
                }
            }
            case '"': return string();
            case 't': p += 4; return Boolean.TRUE;
            case 'f': p += 5; return Boolean.FALSE;
            case 'n': p += 4; return null;
            default: {
                int a = p;
                while (p < s.length() && "+-0123456789.eE".indexOf(s.charAt(p)) >= 0) p++;
                return Double.parseDouble(s.substring(a, p));
            }
        }
    }

    private void expect(char c) {
        if (s.charAt(p) != c) throw new IllegalArgumentException("json: want '" + c + "' at " + p);
        p++;
    }

    private String string() {
        expect('"');
        var b = new StringBuilder();
        while (true) {
            char c = s.charAt(p++);
            if (c == '"') return b.toString();
            if (c != '\\') { b.append(c); continue; }
            char e = s.charAt(p++);
            switch (e) {
                case 'n' -> b.append('\n');
                case 't' -> b.append('\t');
                case 'r' -> b.append('\r');
                case 'b' -> b.append('\b');
                case 'f' -> b.append('\f');
                case 'u' -> { b.append((char) Integer.parseInt(s.substring(p, p + 4), 16)); p += 4; }
                default -> b.append(e);
            }
        }
    }

    static String write(Object v) {
        var b = new StringBuilder();
        write(b, v);
        return b.toString();
    }

    @SuppressWarnings("unchecked")
    private static void write(StringBuilder b, Object v) {
        if (v == null) b.append("null");
        else if (v instanceof Boolean x) b.append(x);
        else if (v instanceof Integer || v instanceof Long) b.append(v);
        else if (v instanceof Number n) {
            double d = n.doubleValue();
            if (!Double.isFinite(d)) b.append("null");
            else if (d == Math.rint(d) && Math.abs(d) < 1e15) b.append((long) d);
            else b.append(Double.toString(d));
        } else if (v instanceof String x) {
            b.append('"');
            for (char c : x.toCharArray()) {
                if (c == '"' || c == '\\') b.append('\\').append(c);
                else if (c < 32) b.append(String.format("\\u%04x", (int) c));
                else b.append(c);
            }
            b.append('"');
        } else if (v instanceof Map<?, ?> m) {
            b.append('{');
            boolean first = true;
            for (var e : ((Map<String, Object>) m).entrySet()) {
                if (!first) b.append(',');
                first = false;
                write(b, e.getKey());
                b.append(':');
                write(b, e.getValue());
            }
            b.append('}');
        } else if (v instanceof List<?> l) {
            b.append('[');
            for (int i = 0; i < l.size(); i++) { if (i > 0) b.append(','); write(b, l.get(i)); }
            b.append(']');
        } else throw new IllegalArgumentException("json: can't write " + v.getClass());
    }
}
