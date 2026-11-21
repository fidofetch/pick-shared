package com.pick.weather;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;

// What the weather grid reads from a World Package once, at load: the ground and the bake's climate over one cube
// face at 128 x 128 cells (the demo island, package face 0, cells of ~135 m). The same bytes the Island Weather Lab
// loads (planning/weather/prototype/island/extract_island.py): fromBytes reads that file, fromPackage makes the same
// numbers straight from a package folder, so the engine and the Builder need no extra file.
//
// Grid axes: i east (the face's 'right'), j SOUTH (row 0 is the face's top edge, the rotation axis +Z being north).
public final class Island {
    public static final int N = 128, CELLS = N * N;
    // yearly / fixed: ground height m (int16), yearly precip mm (uint16), vegetation 0..255, mean temperature x4 (int8),
    // latitude and longitude x100 (int16)
    public final short[] heightM = new short[CELLS];
    public final int[] precipMm = new int[CELLS];
    public final int[] veg255 = new int[CELLS];
    public final byte[] tempQ = new byte[CELLS];
    public final short[] lat100 = new short[CELLS], lon100 = new short[CELLS];
    // twelve months each [m * CELLS + k], quantised as the lab's file: wind toward grid east and grid south x10, wind
    // toward true east x10, the month's share of the year's precip x500, the month's temperature offset x4, cloud
    // cover and convective cloud x255, snow water mm / 4
    public final byte[] windI = new byte[12 * CELLS], windJ = new byte[12 * CELLS], windE = new byte[12 * CELLS];
    public final int[] share500 = new int[12 * CELLS];
    public final byte[] tempSeasonQ = new byte[12 * CELLS];
    public final int[] cloud255 = new int[12 * CELLS], conv255 = new int[12 * CELLS], snow4 = new int[12 * CELLS];
    public int face;   // the package face it came from

    public static final int BYTES = CELLS * (2 + 2 + 1 + 1 + 2 + 2) + 12 * CELLS * 8;

    // The lab's island-data.bin
    public static Island fromBytes(byte[] b) {
        if (b.length != BYTES) throw new IllegalArgumentException("island bytes: " + b.length + ", want " + BYTES);
        ByteBuffer d = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
        Island is = new Island();
        for (int k = 0; k < CELLS; k++) is.heightM[k] = d.getShort();
        for (int k = 0; k < CELLS; k++) is.precipMm[k] = d.getShort() & 0xFFFF;
        for (int k = 0; k < CELLS; k++) is.veg255[k] = d.get() & 0xFF;
        for (int k = 0; k < CELLS; k++) is.tempQ[k] = d.get();
        for (int k = 0; k < CELLS; k++) is.lat100[k] = d.getShort();
        for (int k = 0; k < CELLS; k++) is.lon100[k] = d.getShort();
        d.get(is.windI); d.get(is.windJ); d.get(is.windE);
        for (int i = 0; i < 12 * CELLS; i++) is.share500[i] = d.get() & 0xFF;
        d.get(is.tempSeasonQ);
        for (int i = 0; i < 12 * CELLS; i++) is.cloud255[i] = d.get() & 0xFF;
        for (int i = 0; i < 12 * CELLS; i++) is.conv255[i] = d.get() & 0xFF;
        for (int i = 0; i < 12 * CELLS; i++) is.snow4[i] = d.get() & 0xFF;
        return is;
    }

    public byte[] toBytes() {
        ByteBuffer d = ByteBuffer.allocate(BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (int k = 0; k < CELLS; k++) d.putShort(heightM[k]);
        for (int k = 0; k < CELLS; k++) d.putShort((short) precipMm[k]);
        for (int k = 0; k < CELLS; k++) d.put((byte) veg255[k]);
        for (int k = 0; k < CELLS; k++) d.put(tempQ[k]);
        for (int k = 0; k < CELLS; k++) d.putShort(lat100[k]);
        for (int k = 0; k < CELLS; k++) d.putShort(lon100[k]);
        d.put(windI); d.put(windJ); d.put(windE);
        for (int i = 0; i < 12 * CELLS; i++) d.put((byte) share500[i]);
        d.put(tempSeasonQ);
        for (int i = 0; i < 12 * CELLS; i++) d.put((byte) cloud255[i]);
        for (int i = 0; i < 12 * CELLS; i++) d.put((byte) conv255[i]);
        for (int i = 0; i < 12 * CELLS; i++) d.put((byte) snow4[i]);
        return d.array();
    }

    // ---- straight from a World Package (extract_island.py, in Java) ----------------------------------------------
    // Block means of face `face`: the 512-cell layers by 4, the 256-cell climate by 2. Face 0 is checked against the
    // lab's file (IslandTest); the face's axes come from world.json.
    static final String[] NEED = { "height_m", "precip_mm", "vegetation", "temp_mean_c", "wind_ms", "precip_share",
            "temp_season_c", "cloud_cover", "cloud_convective", "snow_mm" };

    public static boolean hasLayers(Path pkg) {
        try {
            var layers = layers(pkg);
            for (String n : NEED) if (!layers.containsKey(n)) return false;
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> layers(Path pkg) throws IOException {
        return (Map<String, Object>) world(pkg).get("layers");
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> world(Path pkg) throws IOException {
        return (Map<String, Object>) MiniJson.parse(Files.readString(pkg.resolve("world.json")));
    }

    // the face's {normal, right, up} from world.json's grid.faces (the table below when it has none)
    @SuppressWarnings("unchecked")
    static double[][] faceAxes(Map<String, Object> world, int face) {
        if (world.get("grid") instanceof Map<?, ?> g && g.get("faces") instanceof List<?> fs)
            for (Object o : fs) {
                var f = (Map<String, Object>) o;
                if (((Number) f.get("index")).intValue() != face) continue;
                double[][] out = new double[3][3];
                String[] keys = { "normal", "right", "up" };
                for (int r = 0; r < 3; r++) { var v = (List<Object>) f.get(keys[r]); for (int c = 0; c < 3; c++) out[r][c] = ((Number) v.get(c)).doubleValue(); }
                return out;
            }
        return FACES[face];
    }

    public static Island fromPackage(Path pkg, int face) throws IOException {
        var world = world(pkg);
        @SuppressWarnings("unchecked") var layers = (Map<String, Object>) world.get("layers");
        Island is = new Island();
        is.face = face;
        float[] H = block(pkg, layers, "height_m", face, 4)[0], P = block(pkg, layers, "precip_mm", face, 4)[0];
        float[] V = block(pkg, layers, "vegetation", face, 4)[0], T = block(pkg, layers, "temp_mean_c", face, 4)[0];
        float[][] W = block(pkg, layers, "wind_ms", face, 2), S = block(pkg, layers, "precip_share", face, 2);
        float[][] TS = block(pkg, layers, "temp_season_c", face, 2), CC = block(pkg, layers, "cloud_cover", face, 2);
        float[][] CV = block(pkg, layers, "cloud_convective", face, 2), SN = block(pkg, layers, "snow_mm", face, 2);
        double[][] ax = faceAxes(world, face);
        double[] nrm = ax[0], rt = ax[1], up = ax[2];
        for (int b = 0; b < N; b++) for (int a = 0; a < N; a++) {
            int src = b * N + a, dst = (N - 1 - b) * N + a;   // rows flipped: north up
            double ta = Math.tan(((a + 0.5) / N * 2 - 1) * Math.PI / 4), tb = Math.tan(((b + 0.5) / N * 2 - 1) * Math.PI / 4);
            double[] D = norm(add(add(nrm, mul(rt, ta)), mul(up, tb)));
            double[] E = norm(cross(new double[]{ 0, 0, 1 }, D)), Nn = cross(D, E);
            double[] I = tang(rt, D), J = tang(up, D);
            is.heightM[dst] = (short) clampRound(H[src], -32768, 32767);
            is.precipMm[dst] = (int) clampRound(P[src], 0, 65535);
            is.veg255[dst] = (int) clampRound(V[src] * 255, 0, 255);
            is.tempQ[dst] = (byte) clampRound(T[src] * 4, -128, 127);
            is.lat100[dst] = (short) clampRound(Math.toDegrees(Math.asin(D[2])) * 100, -32768, 32767);
            is.lon100[dst] = (short) clampRound(Math.toDegrees(Math.atan2(D[1], D[0])) * 100, -32768, 32767);
            for (int m = 0; m < 12; m++) {
                int o = m * CELLS + dst;
                double we = W[2 * m][src], wn = W[2 * m + 1][src];
                double[] v3 = add(mul(E, we), mul(Nn, wn));
                double wi = dot(v3, I), wj = dot(v3, J);
                is.windI[o] = (byte) clampRound(wi * 10, -127, 127);
                is.windJ[o] = (byte) clampRound(-wj * 10, -127, 127);   // grid y is south
                is.windE[o] = (byte) clampRound(we * 10, -127, 127);
                is.share500[o] = (int) clampRound(S[m][src] * 500, 0, 255);
                is.tempSeasonQ[o] = (byte) clampRound(TS[m][src] * 4, -128, 127);
                is.cloud255[o] = (int) clampRound(CC[m][src] * 255, 0, 255);
                is.conv255[o] = (int) clampRound(CV[m][src] * 255, 0, 255);
                is.snow4[o] = (int) clampRound(SN[m][src] / 4, 0, 255);
            }
        }
        return is;
    }

    // numpy's round (half to even), then clip, as extract_island.py
    static long clampRound(double v, long lo, long hi) { return Math.min(Math.max((long) Math.rint(v), lo), hi); }

    // the face's layer, block-averaged b x b, channels first: [channel][j * N + i] (j = the face's row, not flipped)
    @SuppressWarnings("unchecked")
    static float[][] block(Path pkg, Map<String, Object> layers, String name, int face, int b) throws IOException {
        var spec = (Map<String, Object>) layers.get(name);
        if (spec == null) throw new IOException("the package has no " + name + " layer");
        var shape = (List<Object>) spec.get("shape");
        int n = ((Number) shape.get(1)).intValue(), ch = shape.size() > 3 ? ((Number) shape.get(3)).intValue() : 1;
        if (n != N * b) throw new IOException(name + " is " + n + " cells a side, want " + N * b);
        String dt = (String) spec.get("dtype");
        int sz = switch (dt) { case "float32", "int32" -> 4; case "float16", "int16", "uint16" -> 2; case "uint8" -> 1;
            default -> throw new IOException(name + ": dtype " + dt); };
        double scale = spec.get("scale") instanceof Number s ? s.doubleValue() : 1, offset = spec.get("offset") instanceof Number s ? s.doubleValue() : 0;
        long faceBytes = (long) n * n * ch * sz;
        ByteBuffer buf;
        try (var fc = FileChannel.open(pkg.resolve((String) spec.get("file")), StandardOpenOption.READ)) {
            buf = fc.map(FileChannel.MapMode.READ_ONLY, faceBytes * face, faceBytes).order(ByteOrder.LITTLE_ENDIAN);
        }
        double[][] acc = new double[ch][N * N];
        for (int y = 0; y < n; y++) for (int x = 0; x < n; x++) for (int c = 0; c < ch; c++) {
            int at = ((y * n + x) * ch + c) * sz;
            double v = switch (dt) {
                case "float32" -> buf.getFloat(at);
                case "int32" -> buf.getInt(at);
                case "float16" -> Float.float16ToFloat(buf.getShort(at));
                case "int16" -> buf.getShort(at);
                case "uint16" -> buf.getShort(at) & 0xFFFF;
                default -> buf.get(at) & 0xFF;
            };
            // numpy: a = raw.astype(float32) * scale + offset (float32), then the block mean
            float f = (float) ((float) v * (float) scale + (float) offset);
            acc[c][(y / b) * N + x / b] += f;
        }
        float[][] out = new float[ch][N * N];
        for (int c = 0; c < ch; c++) for (int k = 0; k < N * N; k++) out[c][k] = (float) (acc[c][k] / (b * b));
        return out;
    }

    // the cube's faces as World Package r12's world.json lays them out: {normal, right, up}
    static final double[][][] FACES = {
            { { 1, 0, 0 }, { 0, 1, 0 }, { 0, 0, 1 } },
            { { -1, 0, 0 }, { 0, -1, 0 }, { 0, 0, 1 } },
            { { 0, 1, 0 }, { -1, 0, 0 }, { 0, 0, 1 } },
            { { 0, -1, 0 }, { 1, 0, 0 }, { 0, 0, 1 } },
            { { 0, 0, 1 }, { 0, 1, 0 }, { -1, 0, 0 } },
            { { 0, 0, -1 }, { 0, 1, 0 }, { 1, 0, 0 } } };   // (+Z, -Z)

    static double[] add(double[] a, double[] b) { return new double[]{ a[0] + b[0], a[1] + b[1], a[2] + b[2] }; }
    static double[] mul(double[] a, double s) { return new double[]{ a[0] * s, a[1] * s, a[2] * s }; }
    static double dot(double[] a, double[] b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }
    static double[] cross(double[] a, double[] b) { return new double[]{ a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0] }; }
    static double[] norm(double[] a) { double l = Math.sqrt(dot(a, a)); return mul(a, 1 / l); }
    static double[] tang(double[] v, double[] D) { return norm(add(v, mul(D, -dot(D, v)))); }
}
