package com.pick.launcher;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// The planets on the shelf: every baked World Package the launcher can find, newest first.
//     Planets.find(folders)          the planets in those folders (each a Builder's worlds/ or a folder of packages)
//     planet.colours(maxN)           its surface as a picture of the six cube faces, for its globe
// A World Package is a folder with world.json (format worldpkg); the Builder bakes one into worlds/<id>/bake/world.
final class Planets {
    private Planets() {}

    record Planet(String name, Path pkg, long madeAt, double radiusM, int n) {
        String key() { return pkg.toAbsolutePath().normalize().toString(); }
    }

    static List<Planet> find(List<Path> folders) {
        List<Planet> out = new ArrayList<>();
        for (Path f : folders) {
            if (f == null || !Files.isDirectory(f)) continue;
            try (var kids = Files.list(f)) {
                for (Path k : (Iterable<Path>) kids.sorted()::iterator) {
                    Path pkg = Files.isRegularFile(k.resolve("bake/world/world.json")) ? k.resolve("bake/world")   // a Builder planet
                            : Files.isRegularFile(k.resolve("world.json")) ? k : null;                              // a package itself
                    if (pkg == null) continue;
                    Planet p = read(k, pkg);
                    if (p != null && out.stream().noneMatch(o -> o.key().equals(p.key()))) out.add(p);
                }
            } catch (IOException ignored) { }
        }
        out.sort((a, b) -> Long.compare(b.madeAt, a.madeAt));
        return out;
    }

    private static Planet read(Path entry, Path pkg) {
        try {
            String w = Files.readString(pkg.resolve("world.json"));
            if (!w.contains("\"worldpkg\"")) return null;
            String name = null;
            Path spec = entry.resolve("planet.json");   // the Builder's own description has the name people gave it
            if (Files.isRegularFile(spec)) name = str(Files.readString(spec), "name");
            if (name == null || name.isBlank()) name = entry.getFileName().toString();
            long made = Files.getLastModifiedTime(pkg.resolve("world.json")).toMillis();
            return new Planet(name, pkg, made, num(w, "radius_m", 11000), (int) num(w, "cells_per_face", 0));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    // The six faces side by side (6N x N, RGBA, row j up the face), downsampled to at most maxN a side, and
    // each face's normal, right and up (the package's own grid, so the globe matches the engine)
    record Colours(int n, ByteBuffer rgba, float[][] axes) { }

    static Colours colours(Planet p, int maxN) throws IOException {
        int n = p.n();
        if (n <= 0) throw new IOException("no grid size in " + p.pkg());
        String w = Files.readString(p.pkg().resolve("world.json"));
        float[] height = f32(p.pkg().resolve("layers/height_m.bin"), 6 * n * n);
        float[] ice = optional(() -> f32(p.pkg().resolve("layers/ice_m.bin"), 6 * n * n));
        float[] lake = optional(() -> f32(p.pkg().resolve("layers/lake_depth_m.bin"), 6 * n * n));
        byte[] veg = optional(() -> u8(p.pkg().resolve("layers/vegetation.bin"), 6 * n * n));
        float lo = 0, hi = 1;
        for (float h : height) { lo = Math.min(lo, h); hi = Math.max(hi, h); }

        int step = Math.max(1, (int) Math.ceil(n / (double) maxN)), m = n / step;
        ByteBuffer out = ByteBuffer.allocateDirect(6 * m * m * 4);
        for (int j = 0; j < m; j++)
            for (int f = 0; f < 6; f++)
                for (int i = 0; i < m; i++) {
                    int c = (f * n + j * step) * n + i * step;
                    float h = height[c];
                    float r, g, b;
                    boolean water = h < 0 || (lake != null && lake[c] > 0.5f);
                    if (water) {   // deeper is darker
                        float d = h < 0 ? Math.min(1, h / Math.min(-1, lo)) : 0.15f;
                        r = mix(0.20f, 0.05f, d); g = mix(0.42f, 0.13f, d); b = mix(0.55f, 0.27f, d);
                    } else {
                        float t = Math.min(1, h / hi), v = veg == null ? 0.4f : (veg[c] & 0xFF) / 255f;
                        r = mix(0.66f, 0.27f, v); g = mix(0.56f, 0.42f, v); b = mix(0.38f, 0.20f, v);   // dry to green
                        float rock = Math.max(0, t * 1.6f - 0.6f);                                       // high ground: bare rock
                        r = mix(r, 0.52f, rock); g = mix(g, 0.47f, rock); b = mix(b, 0.42f, rock);
                    }
                    if (ice != null && ice[c] > 1f) { float k = Math.min(1, ice[c] / 30f); r = mix(r, 0.92f, k); g = mix(g, 0.94f, k); b = mix(b, 0.96f, k); }
                    out.put((byte) (r * 255)).put((byte) (g * 255)).put((byte) (b * 255)).put((byte) 255);
                }
        out.flip();
        return new Colours(m, out, faces(w));
    }

    // Each face's normal, right and up, from world.json ("faces": [{..."normal": [1, 0, 0], "right": [...], "up": [...]}])
    private static float[][] faces(String w) {
        float[][] a = new float[6][9];
        Matcher m = Pattern.compile("\"index\"\\s*:\\s*(\\d)[^}]*?\"normal\"\\s*:\\s*\\[([^]]*)][^}]*?\"right\"\\s*:\\s*\\[([^]]*)][^}]*?\"up\"\\s*:\\s*\\[([^]]*)]").matcher(w);
        int found = 0;
        while (m.find()) {
            int f = Integer.parseInt(m.group(1));
            for (int g = 0; g < 3; g++) {
                String[] v = m.group(2 + g).split(",");
                for (int k = 0; k < 3; k++) a[f][g * 3 + k] = Float.parseFloat(v[k].trim());
            }
            found++;
        }
        if (found < 6) throw new IllegalStateException("world.json has no six faces");
        return a;
    }

    private interface Read<T> { T get() throws IOException; }
    private static <T> T optional(Read<T> r) { try { return r.get(); } catch (IOException | RuntimeException e) { return null; } }

    private static float[] f32(Path file, int count) throws IOException {
        var bb = ByteBuffer.wrap(Files.readAllBytes(file)).order(ByteOrder.LITTLE_ENDIAN);
        if (bb.remaining() < count * 4) throw new IOException(file + " is short");
        float[] a = new float[count];
        bb.asFloatBuffer().get(a);
        return a;
    }

    private static byte[] u8(Path file, int count) throws IOException {
        byte[] a = Files.readAllBytes(file);
        if (a.length < count) throw new IOException(file + " is short");
        return a;
    }

    private static float mix(float a, float b, float t) { return a + (b - a) * t; }

    static String str(String json, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(json);
        return m.find() ? m.group(1).replace("\\\"", "\"").replace("\\\\", "\\") : null;
    }

    static double num(String json, String key, double fallback) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(-?[0-9.eE+-]+)").matcher(json);
        return m.find() ? Double.parseDouble(m.group(1)) : fallback;
    }
}
