package com.pick.weather;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// ================= weather_grid: the weather module, in Java =================
// A line-for-line port of the Island Weather Lab's weather-grid.js (module 0.4.0, project files
// planning/weather/prototype/island/, plan planning/weather/planet-weather-grid.md section 4.6): a 2D weather grid
// over the whole demo island (package face 0, 128 x 128 cells of ~135 m, 17 x 17 km). It READS the package's ground and
// climate once (Island) and WRITES its fields each step (vapour, cloud, rain, snow, cloud shapes, the low wind,
// pressure, ...). No engine classes: the engine (a host) and the Builder both use it as it is.
//
// Same seed, same weather, and the same numbers as the lab: the maths keeps the lab's order of operations in doubles,
// the grids hold doubles rounded to float wherever the lab stores a Float32Array (the (float) casts), StrictMath stands
// in for the browser's Math (both fdlibm), and hypot and the integer hashes are done the way JavaScript does them. WeatherGridGoldenTest checks it step for step
// against a run of the lab's file.
//
// Grid axes: x east (the face's 'right'), y SOUTH (rows run north to south). Time: seconds from the start of year 0
// (season 0, the bake's 1 December), 24 h days; the planet's year is four seasons of P.seasonDays each.
// Not thread safe: one thread steps it; a host copies the fields out for others.
public final class WeatherGrid {
    public static final String NAME = "weather_grid", VERSION = "0.4.0";
    public static final int NX = 128, NY = 128, N = NX * NY;
    public static final double DX = 135, DY = 135, PLANET_R = 11000, DT = 10, DOM_W = NX * DX, DOM_H = NY * DY;
    public static final double YEAR = 365 * 86400;
    public static final String[] MONTHS = { "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec" };
    public static final int[] MDAYS = { 31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31 };

    // the settings (the lab's P), defaults as the lab's
    public static final class Params {
        public double day = 1, sunHeat = 1, wind = 1.8, rhIn = 0.66, seaEvap = 5, landEvap = 3, Cw = 0.002, tauF = 150, ceil = 2500, tradeCeil = 1800,
                qs0 = 50, lift = 1.2, subside = 2500, conv = 0.5, stormWater = 2, scale = 0.3, sysSpeed = 3, snowT = 0.5, melt = 3, tilt = 25,
                relaxSea = 1.5, relaxLand = 1.5, frontGap = 96, geo = 6000, thermal = 0.5, breezeK = 6000, breezeLift = 1, catchUp = 6, spinUp = 3;
        public double[][] seasonDays = { { 84, 98 }, { 84, 98 }, { 84, 98 }, { 84, 98 } };
        public double[] seasonGain = { 0.98, 0.9, 0.88, 0.95 };
        public double wetSpell = 3, drySpell = 5, spell = 0.05, dayStorms = 1.5, showerEvery = 900;
    }
    public final Params P = new Params();

    // ---- JavaScript's numbers, exactly ----
    static int toInt32(double d) { return Double.isFinite(d) ? (int) (long) d : 0; }
    static double u32(int x) { return (x & 0xFFFFFFFFL) / 4294967296.0; }
    // V8's Math.hypot (Kahan sum of squares scaled by the largest), so distances match the lab to the bit
    static double hypot(double a, double b) {
        a = Math.abs(a); b = Math.abs(b);
        if (Double.isNaN(a) || Double.isNaN(b)) return Double.isInfinite(a) || Double.isInfinite(b) ? Double.POSITIVE_INFINITY : Double.NaN;
        double max = Math.max(a, b);
        if (max == Double.POSITIVE_INFINITY) return max;
        if (max == 0) return 0;
        double sum = 0, comp = 0;
        double n = a / max, summand = n * n - comp, pre = sum + summand;
        comp = (pre - sum) - summand; sum = pre;
        n = b / max; summand = n * n - comp; pre = sum + summand;
        sum = pre;
        return Math.sqrt(sum) * max;
    }
    static double exp(double x) { return StrictMath.exp(x); }
    static double sin(double x) { return StrictMath.sin(x); }
    static double cos(double x) { return StrictMath.cos(x); }
    static double pow(double x, double y) { return StrictMath.pow(x, y); }

    static double hash2(double x, double y, double s) {
        int h = toInt32(x * 374761393 + y * 668265263 + s * 2147483647);
        h = toInt32((double) (h ^ (h >>> 13)) * 1274126177);
        return u32(h ^ (h >>> 16));
    }
    static double vnoise(double x, double y, double s) {
        double xi = Math.floor(x), yi = Math.floor(y), fx = x - xi, fy = y - yi;
        double u = fx * fx * (3 - 2 * fx), v = fy * fy * (3 - 2 * fy);
        double a = hash2(xi, yi, s), b = hash2(xi + 1, yi, s), c = hash2(xi, yi + 1, s), d = hash2(xi + 1, yi + 1, s);
        return a + (b - a) * u + (c - a) * v + (a - b - c + d) * u * v;
    }
    static double fbm(double x, double y, double s) { return (vnoise(x, y, s) + 0.5 * vnoise(2.03 * x, 2.03 * y, s + 1) + 0.25 * vnoise(4.1 * x, 4.1 * y, s + 2)) / 1.75; }
    static double sstep(double a, double b, double x) { double t = Math.min(Math.max((x - a) / (b - a), 0), 1); return t * t * (3 - 2 * t); }
    static double clamp01(double x) { return Math.min(Math.max(x, 0), 1); }

    // ---- the ground ----
    final double[] H = new double[N], Henv = new double[N], HB = new double[N];
    final double[] GX = new double[N], GY = new double[N], SEAF = new double[N], VEG = new double[N];
    final byte[] SEA = new byte[N];
    final double[] LX = new double[N], LY = new double[N], HAIR = new double[N];
    final double[] PEAK = new double[N], PKH = new double[N], CAPW = new double[N], BAN = new double[N], BANH = new double[N];
    final double[] LGX = new double[N], LGY = new double[N], LLAP = new double[N], RIDGE = new double[N], POOL = new double[N], FOG = new double[N];
    // ---- the bake's climate: yearly, and twelve months ----
    final double[] IP = new double[N], TMEAN = new double[N], LAT = new double[N], LON = new double[N];
    final double[] MWU = new double[12 * N], MWV = new double[12 * N], MUE = new double[12 * N], MSH = new double[12 * N],
            MTS = new double[12 * N], MCC = new double[12 * N], MCV = new double[12 * N], MSN = new double[12 * N];
    // the climate now (months blended)
    final double[] PTEND = new double[N];
    final List<double[]> PH = new ArrayList<>();
    double phT = -1e12;
    final double[] DAYC = new double[N], TCOL = new double[N], CWU = new double[N], CWV = new double[N], CUE = new double[N], TAIR = new double[N], CLDM = new double[N], CONVM = new double[N];
    final double[] TRD = new double[N], WSTL = new double[N], HF = new double[N], QBIAS = new double[N];
    // ---- the weather systems, per cell ----
    final double[] WU = new double[N], WV = new double[N], WST = new double[N], WCV = new double[N], QM = new double[N],
            FB = new double[N], CI = new double[N], INST = new double[N], PP = new double[N], CEIL = new double[N], SPOS = new double[N];
    // ---- the air ----
    double[] Q = new double[N], CS = new double[N], CC = new double[N], A = new double[N];
    double[] Q2 = new double[N], CS2 = new double[N], CC2 = new double[N], A2 = new double[N];
    double[] ANV = new double[N], ANV2 = new double[N];
    final double[] ANVC = new double[N], ANVT = new double[N], MAMMA = new double[N];
    final double[] RAIN = new double[N], FALLS = new double[N], FALLC = new double[N], REACHS = new double[N], REACHC = new double[N];
    final double[] ACC = new double[N], SOIL = new double[N], RH = new double[N], LCL = new double[N];
    final double[] BASES = new double[N], TOPS = new double[N], BASEC = new double[N], TOPC = new double[N];
    final double[] COVS = new double[N], COVC = new double[N], HIGH = new double[N];
    final double[] SNOW = new double[N], SNOWF = new double[N], TG = new double[N], FZL = new double[N];
    final double[] MACC = new double[12 * N];
    final double[] MSEC = new double[12];
    final double[] SHEET = new double[N], LWE = new double[N], LENS = new double[N], LENZ = new double[N], LENT = new double[N];
    final double[] OUTU = new double[N], OUTV = new double[N], GEDGE = new double[N], COLD = new double[N], SHELF = new double[N], DUST = new double[N];
    final double[] LOWU = new double[N], LOWV = new double[N], GUSTY = new double[N], SHEL = new double[N];

    public static final class Sun { public double hour = 12, heat, cool, light = 1, alt = 1, breeze, decl, yf, dayLen = 12, rise, set; }
    public final Sun SUN = new Sun();

    // the module's own random numbers (mulberry32): a seed gives the same weather every time, and the state saves
    int rngS = 1;
    double rnd() {
        rngS = rngS + 0x6D2B79F5;
        int t = rngS;
        t = (t ^ (t >>> 15)) * (t | 1);
        t ^= t + (t ^ (t >>> 7)) * (t | 61);
        return u32(t ^ (t >>> 14));
    }
    double LATC = 0;   // the island's mean land latitude: sets the day's length
    double cellX = 0, cellY = 0, cellMix = 0, nextFront = Double.POSITIVE_INFINITY, simT = 0, accT = 0;
    int cellGen = 0;
    boolean autoFronts = true;

    public static final class Storm {
        public double x, y, r, w, age, life, fuel, spent;
        public boolean shower, day, gust;
    }
    public static final class Gust {
        public double x, y, r, v0, str, age, sp, kids, fade;
        public Storm s;
    }
    public static final class Low {
        public double x, y, vx, vy, R, D, depth, age, life, tx, ty, aW, aC;
        public double[][] warm = new double[0][], cold = new double[0][];
    }
    public static final class High { public double x, y, vx, vy, R, D, depth, age, life; }
    List<Storm> storms = new ArrayList<>();
    List<Gust> gusts = new ArrayList<>();
    List<Low> lows = new ArrayList<>();
    List<High> highs = new ArrayList<>();
    Storm[] near = new Storm[64];
    double[] nearG = new double[64];
    double sysDx = 1, sysDy = 0, sysPmin = 0, sysPmax = 0;   // the direction the systems travel, and the domain's extent along it

    // ---- a pressure system's cross-section ----
    public static final int HIGH_ZONE = 0, SHIELD = 1, SECTOR = 2, COLD_ZONE = 3, POST = 4;
    static final class Prof { double p, dir, spd, wSt, wCv, qm, fb, ci, inst, post; int zone; }
    final Prof PR = new Prof(), PRB = new Prof(), PRC = new Prof();
    static final Prof WARM_SIDE = new Prof();
    static { var w = WARM_SIDE; w.p = -10; w.dir = 215; w.spd = 12; w.wSt = 0.05; w.wCv = 0; w.qm = 1.12; w.fb = 0; w.ci = 0; w.inst = 0.2; }
    double sS() { return 30000 * P.scale; }
    double sW() { return 60000 * P.scale; }
    double sCF() { return 4000 * Math.sqrt(P.scale); }
    double sPF() { return 40000 * P.scale; }
    static Prof high(Prof o) { o.p = 9; o.dir = 260; o.spd = 6; o.wSt = -0.04; o.wCv = 0; o.qm = 0.75; o.fb = 0; o.ci = 0; o.inst = 0.3; o.zone = HIGH_ZONE; o.post = 0; return o; }
    static void lerpTo(Prof o, Prof b, double t) {
        o.p += (b.p - o.p) * t; o.dir += (b.dir - o.dir) * t; o.spd += (b.spd - o.spd) * t; o.wSt += (b.wSt - o.wSt) * t; o.wCv += (b.wCv - o.wCv) * t;
        o.qm += (b.qm - o.qm) * t; o.fb += (b.fb - o.fb) * t; o.ci += (b.ci - o.ci) * t; o.inst += (b.inst - o.inst) * t;
    }
    Prof prof(double s, Prof o) {
        double S = sS(), W = sW(), CF = sCF(), PF = sPF();
        if (s > S + W) high(o);
        else if (s >= S) {
            double u = (s - S) / W;
            o.p = -10 + 18 * u; o.dir = 165 + 80 * u; o.spd = 11 - 4 * u; o.wSt = 0.7 * pow(1 - u, 1.5); o.wCv = 0;
            o.qm = 1.05 - 0.3 * u; o.fb = 150 + 2300 * u; o.ci = sstep(0.3, 0.55, u) * (1 - sstep(0.85, 1, u)); o.inst = 0.05; o.zone = SHIELD; o.post = 0;
            if (s < S + 2500) lerpTo(o, WARM_SIDE, 1 - sstep(S - 2500, S + 2500, s));
        } else if (s >= 0) {
            double v = s / S;
            o.p = -13 + 3 * v; o.dir = 215; o.spd = 12; o.wSt = 0.05; o.wCv = 0; o.qm = 1.12; o.fb = 0; o.ci = 0; o.inst = 0.45; o.zone = SECTOR; o.post = 0;
        } else if (s >= -CF - PF) {
            double t = clamp01((-s - CF) / PF);
            o.p = -13 + 22 * Math.sqrt(t); o.dir = 295 - 25 * t; o.spd = 14 - 7 * t; o.wSt = -0.04 * t; o.wCv = 0;
            o.qm = 0.9 - 0.15 * t; o.fb = 0; o.ci = 0; o.inst = 0.85 * (1 - t) + 0.3 * t; o.zone = s > -CF ? COLD_ZONE : POST; o.post = 1 - t;
            if (t >= 1) high(o);
        } else high(o);
        double d = (s + CF * 0.5) / (CF * 0.5);
        o.wCv += 3.5 * exp(-d * d);
        if (s > -CF && s < CF * 1.5) { double t = sstep(CF * 1.5, -CF, s); o.dir = 215 + (295 - 215) * t; o.spd = Math.max(o.spd, 12 + 3 * sin(Math.PI * t)); }
        double b = (s + CF * 2) / (CF * 1.5);
        o.wSt += 0.12 * exp(-b * b);
        return o;
    }
    double inSystem(double s) { double S = sS(), W = sW(), CF = sCF(), PF = sPF(); return sstep(S + W + 3000, S + W - 3000, s) * sstep(-CF - PF - 3000, -CF - PF + 3000, s); }

    // ---- load ----
    public void load(Island is) {
        for (int i = 0; i < 12 * N; i++) {
            MWU[i] = (float) (is.windI[i] * 0.1); MWV[i] = (float) (is.windJ[i] * 0.1); MUE[i] = (float) (is.windE[i] * 0.1); MSH[i] = (float) (is.share500[i] * (1.0 / 500));
            MTS[i] = (float) (is.tempSeasonQ[i] * 0.25); MCC[i] = (float) (is.cloud255[i] * (1.0 / 255)); MCV[i] = (float) (is.conv255[i] * (1.0 / 255)); MSN[i] = (float) (is.snow4[i] * 4);
        }
        for (int k = 0; k < N; k++) {
            double ih = is.heightM[k];
            boolean land = ih > 0;
            SEA[k] = (byte) (land ? 0 : 1); SEAF[k] = SEA[k]; H[k] = (float) (land ? Math.max(ih, 1) : -15);
            IP[k] = is.precipMm[k]; VEG[k] = (float) (land ? is.veg255[k] / 255.0 : 0); TMEAN[k] = (float) (is.tempQ[k] / 4.0); LAT[k] = (float) (is.lat100[k] / 100.0); LON[k] = (float) (is.lon100[k] / 100.0);
            QBIAS[k] = (float) (SEA[k] != 0 ? 1 : 0.55 + 0.45 * VEG[k]);
        }
        { double a = 0; int n = 0; for (int k = 0; k < N; k++) if (SEA[k] == 0) { a += LAT[k]; n++; } LATC = n > 0 ? a / n : 0; }
        groundFields();
    }
    static double[] blurBox(double[] src, int R, int passes) {
        double[] a = src.clone(), t = new double[N];
        for (int p = 0; p < passes; p++) {
            for (int j = 0; j < NY; j++) for (int i = 0; i < NX; i++) { double s = 0; int n = 0; for (int d = -R; d <= R; d++) { int ii = Math.min(Math.max(i + d, 0), NX - 1); s += a[j * NX + ii]; n++; } t[j * NX + i] = (float) (s / n); }
            for (int j = 0; j < NY; j++) for (int i = 0; i < NX; i++) { double s = 0; int n = 0; for (int d = -R; d <= R; d++) { int jj = Math.min(Math.max(j + d, 0), NY - 1); s += t[jj * NX + i]; n++; } a[j * NX + i] = (float) (s / n); }
        }
        return a;
    }
    void groundFields() {
        double[] src = new double[N]; for (int k = 0; k < N; k++) src[k] = Math.max(H[k], 0);
        System.arraycopy(blurBox(src, 2, 2), 0, Henv, 0, N);
        for (int j = 0; j < NY; j++) for (int i = 0; i < NX; i++) {
            int k = j * NX + i;
            GX[k] = (float) (((double) Henv[j * NX + Math.min(i + 1, NX - 1)] - Henv[j * NX + Math.max(i - 1, 0)]) / (2 * DX));
            GY[k] = (float) (((double) Henv[Math.min(j + 1, NY - 1) * NX + i] - Henv[Math.max(j - 1, 0) * NX + i]) / (2 * DY));
        }
        double[] L = new double[N]; for (int k = 0; k < N; k++) L[k] = SEA[k] != 0 ? 0 : 1;
        double[] Lb = blurBox(L, 7, 2), Hb = blurBox(Henv, 4, 2), Hbb = blurBox(Henv, 10, 2);
        System.arraycopy(Hb, 0, HB, 0, N);
        for (int j = 0; j < NY; j++) for (int i = 0; i < NX; i++) {
            int k = j * NX + i, im = j * NX + Math.max(i - 1, 0), ip = j * NX + Math.min(i + 1, NX - 1), jm = Math.max(j - 1, 0) * NX + i, jp = Math.min(j + 1, NY - 1) * NX + i;
            LGX[k] = (float) (((double) Lb[ip] - Lb[im]) / 2 * 1.35); LGY[k] = (float) (((double) Lb[jp] - Lb[jm]) / 2 * 1.35);
            LLAP[k] = (float) (((double) Lb[ip] + Lb[im] + Lb[jp] + Lb[jm] - 4 * (double) Lb[k]) * 1.8);
            HAIR[k] = (float) Math.max(0.5 * Hbb[k], 0);
            boolean sea = SEA[k] != 0;
            PEAK[k] = (float) (sea ? 0 : clamp01(((double) Henv[k] - Hb[k] - 20) / 70) * clamp01(((double) Henv[k] - 150) / 200));
            RIDGE[k] = (float) (sea ? 0 : clamp01(((double) Hb[k] - Hbb[k]) / 90));
            LX[k] = (float) (0.25 * GX[k] + 0.75 * ((double) Hb[ip] - Hb[im]) / (2 * DX)); LY[k] = (float) (0.25 * GY[k] + 0.75 * ((double) Hb[jp] - Hb[jm]) / (2 * DY));
            POOL[k] = (float) (sea ? 0 : clamp01(0.25 + ((double) Hb[k] - Henv[k]) / 40));
        }
        java.util.Arrays.fill(PKH, 0); java.util.Arrays.fill(CAPW, 0);
        for (int k = 0; k < N; k++) {
            if (PEAK[k] < 0.15) continue;
            int i0 = k % NX, j0 = (k - i0) / NX;
            for (int dj = -3; dj <= 3; dj++) for (int di = -3; di <= 3; di++) {
                int ii = i0 + di, jj = j0 + dj; if (ii < 0 || ii >= NX || jj < 0 || jj >= NY) continue;
                int kk = jj * NX + ii; double w = PEAK[k] * Math.max(1 - hypot(di, dj) / 3.4, 0);
                if (w > CAPW[kk]) CAPW[kk] = (float) w;
                if (Henv[k] > PKH[kk] && w > 0) PKH[kk] = Henv[k];
            }
        }
    }

    // ---- the planet's calendar: four seasons, each a range of days; every year draws its lengths from the seed ----
    int calSeed = 1, calY = 0;
    double calT0 = 0;
    static final double Q0 = 334.0 / 365;   // the bake's year fraction where season 0 starts (1 Dec)
    static double hash01(double a, double b, double c) {
        int t = toInt32(a * 374761393 + b * 668265263 + c * 2246822519.0);
        t = (t ^ (t >>> 13)) * 1274126177;
        t ^= t >>> 16;
        return u32(t);
    }
    double seasonLen(int y, int q) { double a = P.seasonDays[q][0], b = P.seasonDays[q][1]; return Math.round(a + (b - a) * hash01(calSeed, y, q)) * 86400.0; }
    public double yearLen(int y) { return seasonLen(y, 0) + seasonLen(y, 1) + seasonLen(y, 2) + seasonLen(y, 3); }
    public record Cal(int y, int q, double fq, double s0, double len, double y0) {}
    public Cal calendar(double t) {
        int y = calY; double t0 = calT0;
        if (t < t0) { y = 0; t0 = 0; }
        while (t >= t0 + yearLen(y)) { t0 += yearLen(y); y++; }
        calY = y; calT0 = t0;
        double s0 = t0;
        for (int q = 0; q < 4; q++) { double L = seasonLen(y, q); if (t < s0 + L || q == 3) return new Cal(y, q, Math.min((t - s0) / L, 0.999999), s0, L, t0); s0 += L; }
        throw new IllegalStateException();
    }
    double bakeFrac(double t) { if (!Double.isNaN(hostYearFrac)) return hostYearFrac; Cal c = calendar(Math.max(t, 0)); return ((Q0 + (c.q + c.fq) / 4) % 1 + 1) % 1; }
    // A host's own clock (the engine: the air on real time, the day and the year on the game's): when set, the sun's
    // hour (0..24) and the bake's year fraction (0 = 1 January) come from here instead of from simT. NaN (the lab): off.
    public double hostHour = Double.NaN, hostYearFrac = Double.NaN;
    public void hostClock(double hour, double yearFrac) {
        // a jump in the year (or the clock handed back) refreshes the month's climate on the next step
        if (Double.isNaN(yearFrac) != Double.isNaN(hostYearFrac) || Math.abs(yearFrac - SUN.yf) > 0.5 / 365) climT = -1e12;
        hostHour = hour; hostYearFrac = yearFrac;
    }
    // the season position q + fq (0..4) now
    double seasonPos(double t) { if (!Double.isNaN(hostYearFrac)) return (((hostYearFrac - Q0) % 1) + 1) % 1 * 4; Cal c = calendar(Math.max(t, 0)); return c.q + c.fq; }
    // the time in year y when the bake's year fraction is yfb
    public double timeOf(double yfb, int y) {
        double t0 = 0; for (int i = 0; i < y; i++) t0 += yearLen(i);
        double u = (((yfb - Q0) % 1) + 1) % 1 * 4; int q = (int) Math.floor(u); double s = t0; for (int i = 0; i < q; i++) s += seasonLen(y, i);
        return s + (u - q) * seasonLen(y, q);
    }
    public String[] seasonNames() { return LATC < 0 ? new String[]{ "summer", "autumn", "winter", "spring" } : new String[]{ "winter", "spring", "summer", "autumn" }; }
    public record Date(int year, String season, int q, int day, int days, String like) {}
    public Date dateOf(double t) {
        Cal c = calendar(Math.max(t, 0)); double yf = bakeFrac(t); int d = (int) Math.floor(yf * 365), m = 0; while (d >= MDAYS[m]) { d -= MDAYS[m]; m++; }
        return new Date(c.y + 1, seasonNames()[c.q], c.q, (int) Math.floor((t - c.s0) / 86400) + 1, (int) Math.round(c.len / 86400), (d + 1) + " " + MONTHS[m]);
    }
    // wet and dry spells
    boolean spellWet = false;
    double spellUntil = 0, spellHum = 1;
    double seasonGain(double t) {
        double u = seasonPos(t) - 0.5; int q0 = ((int) Math.floor(u) + 4) % 4; double f = u - Math.floor(u);
        return P.seasonGain[q0] * (1 - f) + P.seasonGain[(q0 + 1) % 4] * f;
    }
    void updateSpell(double dt) {
        if (simT >= spellUntil) { spellWet = !spellWet; double mean = spellWet ? P.wetSpell : P.drySpell; spellUntil = simT + (0.4 + 1.2 * rnd()) * mean * 86400; }
        double fw = P.wetSpell / (P.wetSpell + P.drySpell), target = seasonGain(simT) * (spellWet ? 1 + P.spell : 1 - P.spell * fw / (1 - fw));
        spellHum += (target - spellHum) * Math.min(dt / (8 * 3600), 1);
    }
    double rhNow() { return P.rhIn * spellHum; }
    record MonthPos(double yf, int m0, int m1, double f, int m) {}
    MonthPos monthPos(double t) { double yf = bakeFrac(t), mp = yf * 12 - 0.5; int m0 = ((int) Math.floor(mp) + 12) % 12; return new MonthPos(yf, m0, (m0 + 1) % 12, mp - Math.floor(mp), (int) Math.floor(yf * 12)); }
    double climT = -1e12;
    void updateClimate() {
        MonthPos mp = monthPos(simT); int a = mp.m0 * N, b = mp.m1 * N; double f = mp.f;
        SUN.yf = mp.yf; SUN.decl = P.tilt * sin(2 * Math.PI * (mp.yf - 0.22));
        double ch = -StrictMath.tan(LATC * Math.PI / 180) * StrictMath.tan(SUN.decl * Math.PI / 180);
        SUN.dayLen = 24 * StrictMath.acos(Math.min(Math.max(ch, -0.97), 0.97)) / Math.PI;
        for (int k = 0; k < N; k++) {
            CWU[k] = (float) ((MWU[a + k] * (1 - f) + MWU[b + k] * f) * P.wind); CWV[k] = (float) ((MWV[a + k] * (1 - f) + MWV[b + k] * f) * P.wind);
            CUE[k] = (float) (MUE[a + k] * (1 - f) + MUE[b + k] * f);
            TAIR[k] = (float) (TMEAN[k] + MTS[a + k] * (1 - f) + MTS[b + k] * f);
            CLDM[k] = (float) (MCC[a + k] * (1 - f) + MCC[b + k] * f); CONVM[k] = (float) (MCV[a + k] * (1 - f) + MCV[b + k] * f);
            DAYC[k] = (float) clamp01((CONVM[k] - 0.3) / 0.4);
            TRD[k] = (float) clamp01(-CUE[k] / 2.0); WSTL[k] = (float) clamp01(CUE[k] / 2.0);
            HF[k] = (float) Math.max(cos((LAT[k] - SUN.decl) * Math.PI / 180), 0);
        }
        System.arraycopy(blurBox(TAIR, 10, 3), 0, TCOL, 0, N);
        double sx = 0, sy = 0; for (int k = 0; k < N; k++) if (WSTL[k] > 0.5) { sx += CWU[k]; sy += CWV[k]; }
        double sl = hypot(sx, sy); sysDx = sl > 1e-6 ? sx / sl : 1; sysDy = sl > 1e-6 ? sy / sl : 0;
        double c0 = 0 * sysDx + 0 * sysDy, c1 = DOM_W * sysDx + 0 * sysDy, c2 = 0 * sysDx + DOM_H * sysDy, c3 = DOM_W * sysDx + DOM_H * sysDy;
        sysPmin = Math.min(Math.min(c0, c1), Math.min(c2, c3)); sysPmax = Math.max(Math.max(c0, c1), Math.max(c2, c3));
        for (int j = 0; j < NY; j++) for (int i = 0; i < NX; i++) SPOS[j * NX + i] = (float) (((i + 0.5) * DX) * sysDx + ((j + 0.5) * DY) * sysDy);
        climT = simT;
    }
    double qsat(int k) { return P.qs0 * exp(0.065 * (TCOL[k] - 22)); }

    void sunAt(double t, Sun o) {
        double h = Double.isNaN(hostHour) ? (t / 3600) % 24 : hostHour, D = SUN.dayLen, rise = 12 - D / 2, set = 12 + D / 2;
        double alt = h >= rise && h <= set ? sin(Math.PI * (h - rise) / D) : -sin(Math.PI * (((h - set) + 24) % 24) / (24 - D));
        double heat = P.day != 0 ? Math.max(sin(Math.PI * (h - rise - 1.5) / (D + 1)), 0) : 0;
        double sinceDusk = ((h - set - 0.5) + 24) % 24, night = 24 - D;
        double cool = P.day == 0 ? 0 : sinceDusk < night ? Math.min(sinceDusk / (night * 0.75), 1) : h < rise + 3.5 ? (1 - (h - rise - 0.5) / 3) : 0;
        double decl = SUN.decl, yf = SUN.yf;
        o.hour = h; o.alt = alt; o.heat = heat * P.sunHeat; o.cool = Math.max(Math.min(cool, 1), 0); o.light = P.day != 0 ? 0.08 + 0.92 * sstep(-0.26, 0.22, alt) : 1;
        o.rise = rise; o.set = set; o.decl = decl; o.yf = yf; o.dayLen = D;
    }
    public static final class Stats {
        public long day, shower, storm;
        public double oroS, front, banner, oroC, coldF, dayC, gust, stormC, sat;
    }
    public final Stats STATS = new Stats();
    void addStorm(double x, double y, int kind) {   // 0 day, 1 shower, 2 storm
        if (kind == 0) STATS.day++; else if (kind == 1) STATS.shower++; else STATS.storm++;
        Storm s = new Storm(); s.x = x; s.y = y;
        if (kind == 0) { s.r = 900 + 600 * rnd(); s.w = 2.4 + 1.2 * rnd(); s.age = 0; s.life = 1.5 * 3600; s.fuel = 0.8; s.spent = 0; s.shower = true; s.day = true; storms.add(s); return; }
        boolean shower = kind == 1;
        s.r = shower ? 700 + 400 * rnd() : 1500 + 700 * rnd(); s.w = shower ? 1.6 + 1.0 * rnd() : 2 + rnd();
        s.age = 0; s.life = shower ? 1.2 * 3600 : 3.2 * 3600; s.fuel = shower ? 0.6 : 1; s.spent = 0; s.shower = shower;
        storms.add(s);
    }
    double frontGapS() {
        double w = 0; int n = 0; for (int k = 0; k < N; k += 5) if (SEA[k] == 0) { w += WSTL[k]; n++; } w = n > 0 ? w / n : 0;
        return Math.min(P.frontGap * (spellWet ? 0.5 : 1.3) * (0.6 + 0.8 * rnd()) / Math.max(w, 0.1), 240) * 3600;
    }
    // ---- pressure systems ----
    static final double D2R = Math.PI / 180;
    final double[] ZS = new double[N], ZW = new double[N], PSYS = new double[N], PISO = new double[N], SWU = new double[N], SWV = new double[N];
    double lowR() { return 40000 * P.scale; }
    double axHx, axHy, axEx, axEy;
    void sysAxes() { double hx = sysDx, hy = sysDy, ex = -hy, ey = hx; if (ey * (LATC < 0 ? -1 : 1) < 0) { ex = -ex; ey = -ey; } axHx = hx; axHy = hy; axEx = ex; axEy = ey; }
    void sendFront() {
        sysAxes();
        double hx = axHx, hy = axHy, ex = axEx, ey = axEy, R = lowR(), cx = DOM_W / 2, cy = DOM_H / 2;
        double back = (sysPmax - sysPmin) / 2 + 4.5 * R, off = R * (-1.3 + 1.5 * rnd()), drift = 0.04;
        double x = cx - hx * back + ex * (off + drift * back), y = cy - hy * back + ey * (off + drift * back), sp = P.sysSpeed;
        double life = (2 * back + 2 * R) / sp, vx = hx * sp - ex * drift * sp, vy = hy * sp - ey * drift * sp;
        Low L = new Low(); L.x = x; L.y = y; L.vx = vx; L.vy = vy; L.R = R; L.D = 6 + 6 * rnd(); L.depth = 0; L.age = 0; L.life = life;
        lows.add(L);
        High Hh = new High(); Hh.x = x - hx * 5 * R - ex * 0.6 * R; Hh.y = y - hy * 5 * R - ey * 0.6 * R; Hh.vx = vx; Hh.vy = vy; Hh.R = 1.8 * R; Hh.D = 4 + 4 * rnd();
        Hh.depth = 0; Hh.age = 0; Hh.life = life + 5 * R / sp;
        highs.add(Hh);
    }
    double[][] polyline(double x0, double y0, double a0, double bend, double len) {
        int n = 12; double[][] pts = new double[n + 1][]; pts[0] = new double[]{ x0, y0 }; double x = x0, y = y0;
        for (int i = 0; i < n; i++) {
            double a = a0 + bend * (i + 0.5) / n, dx = axHx * cos(a) + axEx * sin(a), dy = axHy * cos(a) + axEy * sin(a);
            x += dx * len / n; y += dy * len / n; pts[i + 1] = new double[]{ x, y };
        }
        return pts;
    }
    static double distPoly(double[][] pts, double x, double y) {
        double best = 1e12;
        for (int i = 0; i + 1 < pts.length; i++) {
            double ax = pts[i][0], ay = pts[i][1], bx = pts[i + 1][0], by = pts[i + 1][1], vx = bx - ax, vy = by - ay;
            double t = Math.min(Math.max(((x - ax) * vx + (y - ay) * vy) / (vx * vx + vy * vy), 0), 1), d = hypot(x - ax - vx * t, y - ay - vy * t);
            if (d < best) best = d;
        }
        return best;
    }
    void moveSystems(double dt) {
        sysAxes();
        for (Low L : lows) {
            L.age += dt; L.x += L.vx * dt; L.y += L.vy * dt;
            double a = L.age / L.life; L.depth = L.D * sstep(0, 0.25, a) * (1 - 0.55 * sstep(0.65, 1, a));
            double occ = L.R * 1.1 * sstep(0.35, 1, a), aW = 30 * D2R, aC = (128 - 58 * sstep(0.1, 0.95, a)) * D2R;
            double tx = L.x + (axHx * cos(aW) + axEx * sin(aW)) * occ, ty = L.y + (axHy * cos(aW) + axEy * sin(aW)) * occ;
            L.tx = tx; L.ty = ty; L.aW = aW; L.aC = aC;
            L.warm = polyline(tx, ty, aW, -12 * D2R, 2.2 * L.R);
            L.cold = polyline(tx, ty, aC, 30 * D2R, 3.3 * L.R);
        }
        for (High Hh : highs) { Hh.age += dt; Hh.x += Hh.vx * dt; Hh.y += Hh.vy * dt; Hh.depth = Hh.D * sstep(0, 0.2, Hh.age / Hh.life) * (1 - sstep(0.8, 1, Hh.age / Hh.life)); }
        boolean gone = false; for (Low L : lows) if (L.age >= L.life) gone = true;
        lows.removeIf(L -> L.age >= L.life); highs.removeIf(Hh -> Hh.age >= Hh.life);
        if (gone && lows.isEmpty()) nextFront = simT + frontGapS();
    }
    // sample: bilinear, 'outside' beyond the grid; sampleC: clamped to the edge
    static double sample(double[] Ar, double x, double y, double outside) {
        if (x < 0 || x > NX - 1 || y < 0 || y > NY - 1) return outside;
        int i = (int) Math.min(Math.floor(x), NX - 2), j = (int) Math.min(Math.floor(y), NY - 2); double fx = x - i, fy = y - j; int k = j * NX + i;
        return (Ar[k] * (1 - fx) + Ar[k + 1] * fx) * (1 - fy) + (Ar[k + NX] * (1 - fx) + Ar[k + NX + 1] * fx) * fy;
    }
    static double sampleC(double[] Ar, double x, double y) { return sample(Ar, Math.min(Math.max(x, 0), NX - 1), Math.min(Math.max(y, 0), NY - 1), 0); }
    double windU(double x, double y) { return sampleC(WU, x / DX - 0.5, y / DY - 0.5); }
    double windV(double x, double y) { return sampleC(WV, x / DX - 0.5, y / DY - 0.5); }

    double sysT = -1e12;
    final double[] PTH = new double[N], TWU = new double[N], TWV = new double[N], CONVT = new double[N];
    void thermalPressure() {
        double[] an = new double[N];
        for (int k = 0; k < N; k++) an[k] = SEA[k] != 0 ? 0 : (float) ((double) TG[k] - TAIR[k]);
        double[] sm = blurBox(an, 6, 2);
        for (int k = 0; k < N; k++) PTH[k] = (float) (-P.thermal * sm[k]);
        for (int j = 0; j < NY; j++) for (int i = 0; i < NX; i++) {
            int k = j * NX + i;
            double gx = ((double) PTH[j * NX + Math.min(i + 1, NX - 1)] - PTH[j * NX + Math.max(i - 1, 0)]) / (2 * DX);
            double gy = ((double) PTH[Math.min(j + 1, NY - 1) * NX + i] - PTH[Math.max(j - 1, 0) * NX + i]) / (2 * DY);
            double sg = LAT[k] >= 0 ? 1 : -1, r = 0.15 * clamp01(Math.abs(LAT[k]) / 8.0);
            TWU[k] = (float) (P.breezeK * (-gx + r * sg * gy)); TWV[k] = (float) (P.breezeK * (-gy - r * sg * gx));
        }
        for (int j = 0; j < NY; j++) for (int i = 0; i < NX; i++) {
            int k = j * NX + i;
            CONVT[k] = (float) -(((double) TWU[j * NX + Math.min(i + 1, NX - 1)] - TWU[j * NX + Math.max(i - 1, 0)]) / (2 * DX) + ((double) TWV[Math.min(j + 1, NY - 1) * NX + i] - TWV[Math.max(j - 1, 0) * NX + i]) / (2 * DY));
        }
    }
    void updateSystems() {
        thermalPressure();
        double S = sS(), CF = sCF();
        sysAxes();
        for (int j = 0; j < NY; j++) for (int i = 0; i < NX; i++) {
            int k = j * NX + i; double x = (i + 0.5) * DX, y = (j + 0.5) * DY;
            double ps = 0, bw = 0, bs = 0;
            for (Low L : lows) {
                double d2 = (x - L.x) * (x - L.x) + (y - L.y) * (y - L.y), R2 = L.R * L.R;
                if (d2 > 30 * R2) continue;
                ps -= L.depth * exp(-d2 / R2);
                double rx = x - L.tx, ry = y - L.ty, phi = StrictMath.atan2(rx * axEx + ry * axEy, rx * axHx + ry * axHy);
                double dw = distPoly(L.warm, x, y), dc = distPoly(L.cold, x, y);
                double sv;
                if (phi > L.aW && phi < L.aC + 0.3) sv = S * dc / Math.max(dc + dw, 1);
                else if (phi >= -Math.PI / 2 && phi <= L.aW) sv = S + dw;
                else sv = -dc;
                double qc = dc / (CF + 900), qw = dw / (CF + 1500);
                ps -= 0.3 * L.depth * (exp(-(qc * qc)) + 0.6 * exp(-(qw * qw))) * exp(-d2 / (9 * R2));
                double w = (L.depth / 10) * exp(-d2 / (12 * R2)) * (0.45 + 0.55 * WSTL[k]);
                if (w > bw) { bw = w; bs = sv; }
            }
            for (High Hh : highs) { double d2 = (x - Hh.x) * (x - Hh.x) + (y - Hh.y) * (y - Hh.y); if (d2 < 30 * Hh.R * Hh.R) ps += Hh.depth * exp(-d2 / (Hh.R * Hh.R)); }
            PSYS[k] = (float) ps; ZW[k] = (float) Math.min(bw, 1); ZS[k] = (float) bs;
        }
        for (int j = 0; j < NY; j++) for (int i = 0; i < NX; i++) {
            int k = j * NX + i;
            double gx = ((double) PSYS[j * NX + Math.min(i + 1, NX - 1)] - PSYS[j * NX + Math.max(i - 1, 0)]) / (2 * DX);
            double gy = ((double) PSYS[Math.min(j + 1, NY - 1) * NX + i] - PSYS[Math.max(j - 1, 0) * NX + i]) / (2 * DY);
            double r = clamp01(Math.abs(LAT[k]) / 8.0), sg = LAT[k] >= 0 ? 1 : -1, fr = SEA[k] != 0 ? 0.3 : 0.55, K = P.geo * P.wind / 1.8;
            SWU[k] = (float) (K * (r * sg * gy - (fr + 0.7 * (1 - r)) * gx));
            SWV[k] = (float) (K * (-r * sg * gx - (fr + 0.7 * (1 - r)) * gy));
        }
        for (int k = 0; k < N; k++) {
            double tr = TRD[k], hi = 1 - tr;
            double wSt = -0.04 * hi, wCv = 0.03 * tr, qm = (0.75 + 0.35 * tr) * (0.75 + 0.6 * CLDM[k]), fb = 0, ci = 0;
            double inst = (0.3 + 0.25 * tr) * (0.6 + 0.8 * CONVM[k]), ceil = P.ceil + (P.tradeCeil - P.ceil) * tr;
            double hiP = Math.max(PSYS[k], 0); if (hiP > 0) { double t = clamp01(hiP / 6); wSt -= 0.04 * t; qm *= 1 - 0.15 * t; inst *= 1 - 0.3 * t; }
            double wz = ZW[k];
            if (wz > 0.02) {
                Prof o = prof(ZS[k], PRC); double wf = wz * inSystem(ZS[k]);
                if (wf > 0) {
                    wSt += (o.wSt - wSt) * wf; wCv += (o.wCv - wCv) * wf; qm += (o.qm - qm) * wf; fb += (o.fb - fb) * wf; ci += (o.ci - ci) * wf;
                    inst += (o.inst - inst) * wf; ceil += (P.ceil - ceil) * wf;
                }
            }
            WU[k] = (float) ((double) CWU[k] + SWU[k] + TWU[k]); WV[k] = (float) ((double) CWV[k] + SWV[k] + TWV[k]);
            WST[k] = (float) wSt; WCV[k] = (float) wCv; QM[k] = (float) qm; FB[k] = (float) fb; CI[k] = (float) ci; INST[k] = (float) inst;
            PP[k] = (float) (1014 + (double) PSYS[k] + PTH[k]); PISO[k] = PP[k]; CEIL[k] = (float) ceil;
        }
    }
    Prof zoneAt(int k) {
        if (ZW[k] < 0.3) return null;
        return prof(ZS[k], PRB);
    }
    void initAir() {
        updateClimate(); System.arraycopy(TAIR, 0, TG, 0, N); updateSystems();
        MonthPos mp = monthPos(simT);
        for (int k = 0; k < N; k++) {
            Q[k] = (float) (rhNow() * qsat(k) * QM[k] * pow(QBIAS[k], 0.6)); CS[k] = CC[k] = A[k] = 0; ANV[k] = 0;
            SOIL[k] = (float) (SEA[k] != 0 ? 1 : 0.15 + 0.6 * VEG[k]);
            SNOW[k] = (float) (SEA[k] != 0 ? 0 : MSN[mp.m0 * N + k] * (1 - mp.f) + MSN[mp.m1 * N + k] * mp.f);
        }
    }

    void lowWind() {
        for (int k = 0; k < N; k++) {
            int land = 1 - SEA[k]; double hf = HF[k];
            double u = WU[k], v = WV[k];
            double sp0 = hypot(u, v), gx = LX[k], gy = LY[k], g2 = gx * gx + gy * gy;
            if (g2 > 1e-6 && sp0 > 0.1) {
                double up = u * gx + v * gy;
                if (up > 0) {
                    double f = clamp01(Math.sqrt(g2) * 6) * 0.7 * clamp01((0.9 - INST[k]) / 0.6);
                    u -= f * up * gx / g2; v -= f * up * gy / g2; double s2 = hypot(u, v); if (s2 == 0) s2 = 1; double keep = sp0 * (1 - 0.25 * f) / s2; u *= keep; v *= keep;
                }
            }
            double fac = (1 + 0.5 * RIDGE[k] + 0.3 * PEAK[k]) * (land != 0 ? (0.78 - 0.22 * VEG[k]) * (1 - 0.4 * SUN.cool * (1 - RIDGE[k]) * (0.5 + 0.5 * POOL[k])) : 1) * (1 - 0.65 * SHEL[k]);
            u *= fac; v *= fac;
            double gl = Math.sqrt(g2);
            if (land != 0 && gl > 0.005) {
                double sl = (1.5 * SUN.heat * hf - 2.5 * SUN.cool * (0.5 + 0.5 * POOL[k] + 0.5 * RIDGE[k])) * Math.min(gl * 25, 1) / gl;
                u += sl * gx; v += sl * gy;
            }
            u += OUTU[k]; v += OUTV[k];
            LOWU[k] = (float) u; LOWV[k] = (float) v;
            GUSTY[k] = (float) ((land != 0 ? 0.22 + 0.1 * VEG[k] : 0.12) + 0.35 * SHEL[k] + 0.15 * SUN.heat * hf * land + 0.45 * Math.min(hypot(OUTU[k], OUTV[k]) / 6, 1));
        }
    }
    double waveT = -1e12;
    void leeWaves() {
        for (int j = 0; j < NY; j += 2) for (int i = 0; i < NX; i += 2) {
            int k = j * NX + i; double u = WU[k], v = WV[k], sp = hypot(u, v);
            if (sp < 3) { LWE[k] = 0; SHEET[k] = 1; SHEL[k] = 0; continue; }
            double lam = Math.min(Math.max(2 * Math.PI * sp / 0.011, 2000), 8000), kw = 2 * Math.PI * DX / lam, ux = u / sp, uy = v / sp;
            double e = 0, hPrev = HB[k];
            for (int n = 1; n <= 45; n++) {
                double h = Math.max(sampleC(HB, i - ux * n, j - uy * n), 0);
                e += (hPrev - h) * cos(kw * (n - 0.5)) * exp(-n / 30.0);
                hPrev = h;
            }
            double tanA = 0; for (int n = 1; n <= 8; n++) { double hu = sampleC(Henv, i - ux * n, j - uy * n); tanA = Math.max(tanA, (hu - Henv[k]) / (n * DX)); }
            SHEL[k] = (float) (SEA[k] != 0 && tanA < 0.03 ? 0 : clamp01((tanA - 0.03) / 0.15));
            SHEET[k] = (float) (0.8 + 0.4 * fbm(((i + 0.5) * DX - 0.3 * cellX) / 5000, ((j + 0.5) * DY - 0.3 * cellY) / 5000, 43));
            LWE[k] = (float) (2 * e * clamp01((sp - 3) / 4));
        }
        double[][] fs = { LWE, SHEET, SHEL };
        for (int j = 0; j < NY; j++) for (int i = 0; i < NX; i++) {
            if ((i & 1) == 0 && (j & 1) == 0) continue;
            int i0 = i & ~1, j0 = j & ~1, i1 = Math.min(i0 + 2, NX - 2), j1 = Math.min(j0 + 2, NY - 2); double fx = (i - i0) / 2.0, fy = (j - j0) / 2.0;
            for (double[] F : fs) F[j * NX + i] = (float) ((F[j0 * NX + i0] * (1 - fx) + F[j0 * NX + i1] * fx) * (1 - fy) + (F[j1 * NX + i0] * (1 - fx) + F[j1 * NX + i1] * fx) * fy);
        }
    }

    public void step(double dt) {
        updateSpell(dt);
        if (simT - climT >= 1800 || simT < climT) updateClimate();
        moveSystems(dt);
        if (lows.isEmpty() && simT >= nextFront && autoFronts) sendFront();
        if (simT - sysT >= 60 || simT < sysT) { updateSystems(); sysT = simT; }
        if (simT - waveT >= 120 || simT < waveT) { leeWaves(); waveT = simT; }
        { double mu = 0, mv = 0; for (int k = 0; k < N; k += 7) { mu += WU[k]; mv += WV[k]; } int n = (N + 6) / 7; cellX += mu / n * dt; cellY += mv / n * dt; }
        cellGen = (int) (Math.floor(simT / 2400) % 7); cellMix = sstep(0, 1, simT / 2400 % 1);
        double fk = !lows.isEmpty() ? lows.get(0).age * P.sysSpeed / 2500 : 0;
        // showers in the cool air behind the cold front
        if (!lows.isEmpty() && rnd() < dt / P.showerEvery) {
            for (int tries = 0; tries < 20; tries++) {
                int k = (int) Math.floor(rnd() * N); Prof o = zoneAt(k);
                if (o != null && o.zone == POST && rnd() < o.post * ZW[k]) { addStorm((k % NX + 0.5) * DX, (Math.floor(k / NX) + 0.5) * DY, 1); break; }
            }
        }
        sunAt(simT, SUN);
        SUN.breeze = 4 * SUN.heat - 1.5 * SUN.cool;
        // afternoon storms where the breezes and upslope winds meet
        if (SUN.heat > 0.55 && rnd() < (SUN.heat - 0.55) * P.dayStorms * (spellWet ? 1.4 : 0.7) * dt / 1500) {
            double best = -1, bx = 0, by = 0;
            for (int tries = 0; tries < 40; tries++) {
                int k = (int) Math.floor(rnd() * N); if (SEA[k] != 0) continue;
                double sc = (RIDGE[k] + Math.min(Math.max(CONVT[k] * 350 * P.breezeLift, 0), 0.8) + RH[k] - 0.6 * COVS[k]) * HF[k] * DAYC[k] * (0.5 + INST[k]) + 0.3 * rnd();
                if (sc > best) { best = sc; bx = (k % NX + 0.5) * DX; by = (Math.floor(k / NX) + 0.5) * DY; }
            }
            if (best > 0.9) addStorm(bx, by, 0);
        }
        // banners behind sharp peaks
        java.util.Arrays.fill(BAN, 0);
        for (int k = 0; k < N; k++) {
            double pk = PEAK[k]; if (pk < 0.15) continue;
            int i0 = k % NX, j0 = (k - i0) / NX; double u = WU[k], v = WV[k], sp = hypot(u, v); if (sp < 4) continue;
            double ux = u / sp, uy = v / sp, str = pk * clamp01((sp - 4) / 8);
            for (int n = 1; n <= 7; n++) {
                double sh = n < 2 ? 0.6 : exp(-(n - 2) / 2.3), wm = 1 + n / 3.0;
                int wc = (int) Math.ceil(wm);
                for (int w = -wc; w <= wc; w++) {
                    int ii = (int) Math.round(i0 + ux * n - uy * w * 0.8), jj = (int) Math.round(j0 + uy * n + ux * w * 0.8);
                    if (ii < 0 || ii >= NX || jj < 0 || jj >= NY) continue;
                    int kk = jj * NX + ii; double val = str * sh * exp(-(w / wm) * (w / wm) * 1.5);
                    if (val > BAN[kk]) { BAN[kk] = (float) val; BANH[kk] = Henv[k]; }
                }
            }
        }
        // storms drift, held back by high ground, spend their water, refill over the sea
        for (Storm s : storms) {
            double gi = s.x / DX - 0.5, gj = s.y / DY - 0.5; boolean inside = gi >= 0 && gi <= NX - 1 && gj >= 0 && gj <= NY - 1;
            double h = inside ? sample(Henv, gi, gj, 0) : 0; boolean sea = !inside || sample(SEAF, gi, gj, 1) > 0.5;
            double slow = 1 / (1 + h * P.lift / 120);
            double u = windU(s.x, s.y), v = windV(s.x, s.y);
            s.x += u * dt * slow; s.y += v * dt * slow; s.age += dt;
            s.fuel = clamp01(s.fuel - s.spent / (Math.PI * s.r * s.r / (DX * DY) * P.stormWater));
            if (sea) s.fuel = Math.min(s.fuel + dt / 1800, 1);
            s.spent = 0;
        }
        // gust fronts
        for (Storm s : storms) if (!s.gust && s.age > Math.min(0.3 * s.life, 1500) && s.fuel > 0.15 && gusts.size() < 12) {
            s.gust = true;
            Gust g = new Gust(); g.x = s.x; g.y = s.y; g.r = s.r * 0.6; g.v0 = 3 + 2 * s.w * (0.5 + 0.5 * s.fuel); g.str = clamp01(0.35 + 0.25 * s.w * s.fuel);
            g.age = 0; g.sp = 1; g.kids = 0; g.s = s; g.fade = 1;
            gusts.add(g);
        }
        for (Gust g : gusts) {
            if (g.s != null && storms.contains(g.s)) { g.x = g.s.x; g.y = g.s.y; }
            else { g.s = null; double u = windU(g.x, g.y), v = windV(g.x, g.y); g.x += u * 0.6 * dt; g.y += v * 0.6 * dt; }
            g.sp = g.v0 * exp(-g.age / 1100); g.r += g.sp * dt; g.age += dt; g.fade = exp(-g.age / 1500);
        }
        gusts.removeIf(g -> !(g.sp > 0.7 && g.age < 3600));
        java.util.Arrays.fill(OUTU, 0); java.util.Arrays.fill(OUTV, 0); java.util.Arrays.fill(GEDGE, 0); java.util.Arrays.fill(COLD, 0);
        for (Gust g : gusts) {
            double R = g.r + 600; int i0 = (int) Math.max(Math.floor((g.x - R) / DX), 0), i1 = (int) Math.min(Math.ceil((g.x + R) / DX), NX - 1);
            int j0 = (int) Math.max(Math.floor((g.y - R) / DY), 0), j1 = (int) Math.min(Math.ceil((g.y + R) / DY), NY - 1);
            double wu = windU(g.x, g.y), wv = windV(g.x, g.y), ws = hypot(wu, wv); if (ws == 0) ws = 1;
            for (int j = j0; j <= j1; j++) for (int i = i0; i <= i1; i++) {
                int k = j * NX + i; double dx = (i + 0.5) * DX - g.x, dy = (j + 0.5) * DY - g.y, d = hypot(dx, dy); if (d == 0) d = 1;
                double lead = 0.45 + 0.55 * Math.max((dx * wu + dy * wv) / (d * ws), 0);
                double out = g.sp * 1.4 * g.str * g.fade * lead * sstep(0.25 * g.r, 0.92 * g.r, d) * (1 - sstep(g.r, g.r + 180, d));
                OUTU[k] = (float) (OUTU[k] + out * dx / d); OUTV[k] = (float) (OUTV[k] + out * dy / d);
                double cold = g.str * g.fade * (1 - sstep(0.85 * g.r, g.r + 100, d)); if (cold > COLD[k]) COLD[k] = (float) cold;
                double q = (d - g.r - 60) / 260, e = g.str * g.fade * lead * exp(-(q * q)); if (e > GEDGE[k]) GEDGE[k] = (float) e;
            }
            if (g.kids < 2 && storms.size() < 24 && rnd() < g.str * g.fade * dt / 900) {
                double a = StrictMath.atan2(wv, wu) + (rnd() - 0.5) * 2.2, x = g.x + cos(a) * g.r, y = g.y + sin(a) * g.r;
                double gi = x / DX - 0.5, gj = y / DY - 0.5;
                if (gi > 0 && gi < NX - 1 && gj > 0 && gj < NY - 1 && sample(RH, gi, gj, 0) > 0.55 && sample(INST, gi, gj, 0) > 0.3) { addStorm(x, y, 0); g.kids++; }
            }
        }
        for (int k = 0; k < N; k++) PP[k] = (float) (PISO[k] + 2.5 * COLD[k]);
        // the pressure 3 h ago (a snapshot every 15 min)
        if (simT - phT >= 900 || simT < phT) { if (phT < 0 || simT < phT || simT - phT > 3 * 3600) PH.clear(); PH.add(PP.clone()); if (PH.size() > 13) PH.remove(0); phT = simT; }
        { double[] old = PH.isEmpty() ? null : PH.get(0); for (int k = 0; k < N; k++) PTEND[k] = old != null ? (float) ((double) PP[k] - old[k]) : 0; }
        storms.removeIf(s -> !(s.age < s.life && s.x > -4000 && s.x < DOM_W + 4000 && s.y > -4000 && s.y < DOM_H + 4000));
        double seaE = P.seaEvap / 86400, landE = P.landEvap / 86400, tauS = P.tauF * 2.5; int mNow = monthPos(simT).m, mOff = mNow * N;
        MSEC[mNow] += dt;
        double rhN = rhNow();
        Stats C = STATS;
        for (int j = 0; j < NY; j++) for (int i = 0; i < NX; i++) {
            int k = j * NX + i; double spd = 1 + 0.6 * Henv[k] / 500, hf = HF[k];
            double u = ((double) WU[k] - TWU[k]) * spd + TWU[k] + OUTU[k], v = ((double) WV[k] - TWV[k]) * spd + TWV[k] + OUTV[k];
            double xb = i - u * dt / DX, yb = j - v * dt / DY;
            double qsk = qsat(k), inflow = rhN * qsk * QM[k];
            double q = sample(Q, xb, yb, inflow), cs = sample(CS, xb, yb, 0), cc = sample(CC, xb, yb, 0), an = sample(A, xb, yb, 0);
            double wOro = (u * LX[k] + v * LY[k]) * P.lift, wSt = WST[k], inst = INST[k];
            double wS = 0; int nNear = 0;
            double x = (i + 0.5) * DX, y = (j + 0.5) * DY;
            for (Storm s : storms) {
                double d2 = (x - s.x) * (x - s.x) + (y - s.y) * (y - s.y); if (d2 > 9 * s.r * s.r) continue;
                double g = exp(-d2 / (s.r * s.r)); if (g < 0.03) continue;
                double env = sin(Math.PI * Math.min(s.age / s.life, 1));
                wS += s.w * Math.sqrt(env) * (0.15 + 0.85 * s.fuel) * g;
                if (nNear == near.length) { near = java.util.Arrays.copyOf(near, 2 * nNear); nearG = java.util.Arrays.copyOf(nearG, 2 * nNear); }
                near[nNear] = s; nearG[nNear++] = g;
            }
            if (wS > 0) q += P.conv * wS * q * dt / 1000;
            int land = 1 - SEA[k]; double heat = SUN.heat * hf;
            double tg = TAIR[k] + 4 * heat * land - 4 * SUN.cool * land * (1 - 0.6 * Math.max(COVS[k], COVC[k]));
            TG[k] = (float) tg;
            double qs = qsk * (1 + 0.2 * heat * land);
            double rh = q / qs;
            double fo = clamp01((rh - 0.55) / 0.35), fs = clamp01((rh - 0.28) / 0.5), fw = clamp01((rh - 0.22) / 0.45);
            double blob = Math.max(0.2 + 1.6 * (fbm((x - cellX) / 1700, (y - cellY) / 1700, 41) - 0.3), 0.15);
            double oro = Math.max(wOro, 0) * fo * blob;
            double wBan = BAN[k] * hypot(u, v) * 0.12 * fo;
            double Ss = P.Cw * (oro * (1 - inst) + Math.max(wSt, 0) * fw + wBan) * dt;
            double Sstorm = P.Cw * wS * fs * dt;
            double wCv = WCV[k];
            if (wCv > 0.1) { double along = y * sysDx - x * sysDy, fb = fbm(along / 1400, fk, 21); wCv *= 0.25 + 1.5 * (fb * fb) * 1.6; }
            double cn = vnoise((x - cellX) / 750, (y - cellY) / 750, 31 + cellGen) * (1 - cellMix) + vnoise((x - cellX) / 750 + 17.3, (y - cellY) / 750 + 5.1, 32 + cellGen) * cellMix;
            double cell = Math.min(Math.max((cn - 0.38) * 2.6, 0), 1.4);
            double wDay = land * heat * DAYC[k] * (0.12 + 0.9 * RIDGE[k] + Math.min(Math.max(CONVT[k] * 700 * P.breezeLift, 0), 1.5)) * (1 - 0.6 * COVS[k]) * (1 - COLD[k]);
            double wGust = GEDGE[k] * 1.6 * (0.3 + 0.7 * inst);
            double Sc = P.Cw * (oro * inst * cell * (1 - 0.7 * COLD[k]) + wCv * fs * (0.4 + 0.6 * cell) + wDay * fs * (0.25 + 0.75 * cell) + wGust * fs * (0.4 + 0.6 * cell)) * dt + Sstorm;
            if (land != 0) {
                double cw = P.Cw * dt; C.oroS += cw * oro * (1 - inst); C.front += cw * Math.max(wSt, 0) * fw; C.banner += cw * wBan;
                C.oroC += cw * oro * inst * cell * (1 - 0.7 * COLD[k]); C.coldF += cw * wCv * fs * (0.4 + 0.6 * cell); C.dayC += cw * wDay * fs * (0.25 + 0.75 * cell); C.gust += cw * wGust * fs * (0.4 + 0.6 * cell); C.stormC += Sstorm;
            }
            double tot = Ss + Sc, lim = q * 0.5;
            if (tot > lim) { Ss *= lim / tot; Sc *= lim / tot; }
            q -= Ss + Sc; cs += Ss; cc += Sc;
            if (Sstorm > 0) for (int m = 0; m < nNear; m++) near[m].spent += Sstorm * nearG[m];
            double chill = 0.55 * SUN.cool * (0.3 + 0.7 * POOL[k]) * (1 - 0.7 * Math.max(COVS[k], COVC[k]));
            double fogNow = land * clamp01((q / (qs * (1 - chill)) - 0.9) / 0.1);
            if (q > qs) { double ex = (q - qs) * 0.5; q -= ex; cs += ex; if (land != 0) C.sat += ex; }
            double fe = 0;
            if (wOro < 0 && BAN[k] < 0.2) { fe += Math.min(-wOro * dt / 150, 1); q *= exp(wOro * dt / P.subside); }
            if (wSt < 0) fe += Math.min(-wSt * dt / 150, 1);
            rh = q / qs;
            if (rh < 0.8) fe += Math.min(dt / 900 * (0.8 - rh) / 0.3, 1);
            fe = Math.min(fe, 1);
            q += (cs + cc) * fe; cs *= 1 - fe; cc *= 1 - fe;
            double fallS = Math.max(cs - 0.04, 0) * Math.min(dt / tauS, 1), fallC = Math.max(cc - 0.04, 0) * Math.min(dt / P.tauF, 1);
            cs -= fallS; cc -= fallC;
            rh = Math.min(q / qs, 1);
            double lcl = Math.min(Math.max(25 * (100 - rh * 100), 60), 2200);
            double baseS = Math.max(lcl, FB[k] > 0 ? FB[k] : lcl);
            int snowing = tg < P.snowT ? 1 : 0; double rs = snowing != 0 ? 500 : 350;
            double reachC = exp(-(1 - rh) * lcl / rs), reachS = exp(-(1 - rh) * baseS / rs - Math.max(baseS - lcl, 0) / 1500);
            double ground = fallS * reachS + fallC * reachC;
            q += fallS + fallC - ground;
            if (SEA[k] != 0) q += seaE * dt * qsk / P.qs0;
            else {
                double e = landE * SOIL[k] * dt * Math.min(qsk / P.qs0, 1.3) * (SNOW[k] > 5 ? 0.3 : 1); q += e;
                SOIL[k] = (float) clamp01(SOIL[k] + (snowing != 0 ? 0 : ground) / 40 - e / 40);
                if (snowing != 0) SNOW[k] = (float) (SNOW[k] + ground);
                if (tg > 0 && SNOW[k] > 0) { double melt = Math.min(SNOW[k], P.melt * tg * dt / 86400 * (1 + 0.5 * heat)); SNOW[k] = (float) (SNOW[k] - melt); SOIL[k] = (float) clamp01(SOIL[k] + melt / 40); }
            }
            q += (rhN * qsk * QM[k] * pow(QBIAS[k], 0.6) - q) * dt / ((SEA[k] != 0 ? P.relaxSea : P.relaxLand) * 3600);
            double gnd = Math.max(H[k], 0), ref = HAIR[k];
            double topC = ref + lcl + 120 + 1300 * (1 - exp(-cc / 0.6)) + 900 * Math.min(wS + wCv, 1.2) * (1 - exp(-cc / 0.2));
            an += CI[k] * dt / 900 * (1 - an);
            an *= exp(-dt / 1800);
            double xa = i - WU[k] * 1.6 * dt / DX, ya = j - WV[k] * 1.6 * dt / DY;
            double av = sample(ANV, xa, ya, 0);
            double ceil = CEIL[k], push = clamp01((topC - (ceil - 500)) / 500);
            if (push > 0) av += Math.min(cc, 1.5) * 0.25 * push * dt / 10;
            av *= exp(-dt / 3600);
            ANV2[k] = (float) Math.min(av, 2.5);
            Q2[k] = (float) q; CS2[k] = (float) cs; CC2[k] = (float) cc; A2[k] = (float) an;
            RAIN[k] = (float) (ground / dt * 3600); FALLS[k] = (float) (fallS / dt * 3600); FALLC[k] = (float) (fallC / dt * 3600); REACHS[k] = (float) reachS; REACHC[k] = (float) reachC;
            ACC[k] = (float) (ACC[k] + ground); MACC[mOff + k] = (float) (MACC[mOff + k] + ground); RH[k] = (float) rh; LCL[k] = (float) lcl; SNOWF[k] = snowing;
            FZL[k] = (float) (gnd + tg * 1000 / 6.5);
            BASEC[k] = (float) Math.max(ref + lcl, gnd + 30); TOPC[k] = (float) Math.max(Math.min(topC, ceil), BASEC[k] + 80.0);
            BASES[k] = (float) Math.min(Math.max(ref + baseS, gnd + 30), ceil - 200);
            TOPS[k] = (float) Math.max(Math.min(BASES[k] + 150 + 450 * (1 - exp(-cs / 0.5)) + 1500 * clamp01(wSt / 0.5) * (1 - exp(-cs / 0.4)), ceil), BASES[k] + 100.0);
            COVS[k] = (float) (1 - exp(-cs / 0.07)); COVC[k] = (float) (1 - exp(-cc / 0.07)); HIGH[k] = (float) an;
            if (CAPW[k] > 0.08 && COVS[k] > 0.2 && BASES[k] < PKH[k] + 250.0) {
                double c = clamp01(CAPW[k] * 1.5);
                BASES[k] = (float) (BASES[k] + (Math.max(PKH[k] - 70.0, gnd + 5) - BASES[k]) * c); TOPS[k] = (float) Math.max(TOPS[k], PKH[k] + 100 + 140 * c);
                COVS[k] = (float) Math.max(COVS[k], 0.9 * c * COVS[k] / 0.6);
            }
            if (BAN[k] > 0.1 && (COVS[k] > 0.1 || rh > 0.8)) {
                double b = clamp01(BAN[k] * 2.0), wet = Math.max(clamp01((rh - 0.8) / 0.12), clamp01(COVS[k] / 0.3));
                BASES[k] = (float) (BASES[k] + (Math.max(BANH[k] - 260.0, gnd + 10) - BASES[k]) * b); TOPS[k] = (float) (TOPS[k] + (BANH[k] + 110.0 - TOPS[k]) * b);
                COVS[k] = (float) Math.max(COVS[k], 0.8 * b * wet);
            }
            SHELF[k] = (float) (GEDGE[k] * clamp01((rh - 0.45) / 0.3));
            if (SHELF[k] > 0.08) {
                double sh = clamp01(SHELF[k] * 1.6);
                BASES[k] = (float) (BASES[k] + (Math.max(HAIR[k] + 0.5 * lcl, gnd + 120) - BASES[k]) * sh); TOPS[k] = (float) Math.max(TOPS[k], BASES[k] + 350 + 700 * sh);
                COVS[k] = (float) Math.max(COVS[k], 0.85 * sh);
            }
            double gs = hypot(OUTU[k], OUTV[k]), gt = hypot((double) WU[k] + OUTU[k], (double) WV[k] + OUTV[k]);
            double dNow = land * clamp01((gs - 1) / 2) * clamp01((gt - 6) / 4) * clamp01((0.45 - SOIL[k]) / 0.3) * (1 - VEG[k] * 0.6) * (RAIN[k] > 0.3 || SNOW[k] > 2 ? 0 : 1);
            DUST[k] = (float) (DUST[k] + (dNow - DUST[k]) * Math.min(dt / (dNow > DUST[k] ? 60 : 600), 1));
            FOG[k] = (float) (FOG[k] + (fogNow - FOG[k]) * Math.min(dt / 600, 1));
            if (FOG[k] > 0.05) {
                double fz = gnd + 25 + 90 * FOG[k] * (0.5 + POOL[k]);
                BASES[k] = (float) (BASES[k] + (gnd - 8 - BASES[k]) * FOG[k]); TOPS[k] = (float) Math.max(TOPS[k] * (1 - FOG[k]) + fz * FOG[k], BASES[k] + 20.0);
                COVS[k] = (float) Math.max(COVS[k], 0.95 * FOG[k]);
            }
        }
        // lenticulars on the lee waves' crests
        for (int k = 0; k < N; k++) {
            double stab = clamp01((0.9 - INST[k]) / 0.4) * (1 - COVC[k]), gnd = Math.max(H[k], 0);
            double zL = Math.min(HAIR[k] + 1300.0, CEIL[k] - 200.0);
            double rhA = rhN * QM[k] * 0.55 * SHEET[k] * exp(0.4225 * (zL - gnd) / 1000), need = Math.max(1 - rhA, 0) * 380;
            double c = clamp01((LWE[k] - need) / 50) * stab;
            LENS[k] = (float) (LENS[k] + (c - LENS[k]) * Math.min(dt / 600, 1));
            LENZ[k] = (float) (zL + 0.5 * Math.max(LWE[k], 0)); LENT[k] = (float) (120 + 380 * LENS[k]);
        }
        boolean spread = Math.floor(simT / dt) % 3 == 0;
        for (int j = 0; j < NY; j++) for (int i = 0; i < NX; i++) {
            int k = j * NX + i; double v = ANV2[k];
            if (spread) { double m = Math.max(Math.max(i > 0 ? ANV2[k - 1] : 0, i < NX - 1 ? ANV2[k + 1] : 0), Math.max(j > 0 ? ANV2[k - NX] : 0, j < NY - 1 ? ANV2[k + NX] : 0)); v = Math.max(v, 0.8 * m); }
            ANV[k] = (float) v; ANVC[k] = (float) (1 - exp(-v / 0.3)); ANVT[k] = (float) (120 + 520 * clamp01(v / 1.2));
            MAMMA[k] = (float) (clamp01((v - 0.2) / 0.4) * (1 - 0.75 * COVC[k]));
        }
        lowWind();
        double[] t = Q; Q = Q2; Q2 = t; t = CS; CS = CS2; CS2 = t; t = CC; CC = CC2; CC2 = t; t = A; A = A2; A2 = t;
        simT += dt; accT += dt;
    }

    // ---- saving, loading and catching up ----
    // The state that carries from step to step: the air, the ground, what the sky looked like last step, the systems
    // and storms, the clock and the random numbers. Everything else is rebuilt from the package and these on load.
    // The bytes are the lab's (format 'weather_grid state 2'): a state saved by either loads in the other.
    static final String[] SAVED = { "Q", "CS", "CC", "A", "ANV", "SNOW", "SOIL", "TG", "COVS", "COVC", "RH", "SHEET", "LWE", "SHEL", "DUST", "FOG", "LENS", "PH0" };
    double[] saved(String n) {
        return switch (n) {
            case "Q" -> Q; case "CS" -> CS; case "CC" -> CC; case "A" -> A; case "ANV" -> ANV; case "SNOW" -> SNOW; case "SOIL" -> SOIL;
            case "TG" -> TG; case "COVS" -> COVS; case "COVC" -> COVC; case "RH" -> RH; case "SHEET" -> SHEET; case "LWE" -> LWE; case "SHEL" -> SHEL;
            case "DUST" -> DUST; case "FOG" -> FOG; case "LENS" -> LENS; case "PH0" -> PH.isEmpty() ? PP : PH.get(0);
            default -> null;
        };
    }
    public byte[] saveState() {
        var head = new LinkedHashMap<String, Object>();
        head.put("format", "weather_grid state 2"); head.put("version", VERSION); head.put("N", N);
        var sp = new LinkedHashMap<String, Object>(); sp.put("wet", spellWet); sp.put("until", spellUntil); sp.put("hum", spellHum); head.put("spell", sp);
        head.put("calSeed", calSeed); head.put("simT", simT); head.put("accT", accT); head.put("cellX", cellX); head.put("cellY", cellY);
        head.put("nextFront", nextFront); head.put("autoFronts", autoFronts); head.put("rngS", rngS); head.put("phT", phT); head.put("phN", PH.size());
        var ss = new ArrayList<Object>();
        for (Storm s : storms) {
            var m = new LinkedHashMap<String, Object>();
            m.put("x", s.x); m.put("y", s.y); m.put("r", s.r); m.put("w", s.w); m.put("age", s.age); m.put("life", s.life); m.put("fuel", s.fuel); m.put("spent", s.spent);
            m.put("shower", s.shower); if (s.day) m.put("day", true); if (s.gust) m.put("gust", true);
            ss.add(m);
        }
        head.put("storms", ss);
        var gs = new ArrayList<Object>();
        for (Gust g : gusts) {
            var m = new LinkedHashMap<String, Object>();
            m.put("x", g.x); m.put("y", g.y); m.put("r", g.r); m.put("v0", g.v0); m.put("str", g.str); m.put("age", g.age); m.put("sp", g.sp); m.put("kids", g.kids);
            m.put("s", g.s == null ? -1 : storms.indexOf(g.s)); m.put("fade", g.fade);
            gs.add(m);
        }
        head.put("gusts", gs);
        var ls = new ArrayList<Object>();
        for (Low L : lows) { var m = new LinkedHashMap<String, Object>(); m.put("x", L.x); m.put("y", L.y); m.put("vx", L.vx); m.put("vy", L.vy); m.put("R", L.R); m.put("D", L.D); m.put("age", L.age); m.put("life", L.life); ls.add(m); }
        head.put("lows", ls);
        var hs = new ArrayList<Object>();
        for (High Hh : highs) { var m = new LinkedHashMap<String, Object>(); m.put("x", Hh.x); m.put("y", Hh.y); m.put("vx", Hh.vx); m.put("vy", Hh.vy); m.put("R", Hh.R); m.put("D", Hh.D); m.put("depth", Hh.depth); m.put("age", Hh.age); m.put("life", Hh.life); hs.add(m); }
        head.put("highs", hs);
        head.put("arrays", List.of(SAVED));
        byte[] js = MiniJson.write(head).getBytes(StandardCharsets.UTF_8);
        int pad = (4 - (js.length + 4) % 4) % 4;
        ByteBuffer out = ByteBuffer.allocate(4 + js.length + pad + SAVED.length * N * 4).order(ByteOrder.LITTLE_ENDIAN);
        out.putInt(js.length + pad); out.put(js); for (int i = 0; i < pad; i++) out.put((byte) 32);
        for (String nm : SAVED) for (double f : saved(nm)) out.putFloat((float) f);
        return out.array();
    }
    static double num(Map<String, Object> m, String k) { Object v = m.get(k); return v instanceof Number n ? n.doubleValue() : v == null && "nextFront".equals(k) ? Double.POSITIVE_INFINITY : 0; }
    static boolean bool(Map<String, Object> m, String k) { return Boolean.TRUE.equals(m.get(k)); }
    @SuppressWarnings("unchecked")
    public void loadState(byte[] bytes) {
        ByteBuffer d = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int hl = d.getInt(0);
        var head = (Map<String, Object>) MiniJson.parse(new String(bytes, 4, hl, StandardCharsets.UTF_8));
        if ((int) num(head, "N") != N) throw new IllegalArgumentException("weather state is for another grid");
        var sp = (Map<String, Object>) head.get("spell"); spellWet = bool(sp, "wet"); spellUntil = num(sp, "until"); spellHum = num(sp, "hum");
        calSeed = (int) num(head, "calSeed"); calY = 0; calT0 = 0; simT = num(head, "simT"); accT = num(head, "accT"); cellX = num(head, "cellX"); cellY = num(head, "cellY");
        nextFront = num(head, "nextFront"); autoFronts = bool(head, "autoFronts"); rngS = (int) (long) num(head, "rngS");
        storms = new ArrayList<>();
        for (Object o : (List<Object>) head.get("storms")) {
            var m = (Map<String, Object>) o; Storm s = new Storm();
            s.x = num(m, "x"); s.y = num(m, "y"); s.r = num(m, "r"); s.w = num(m, "w"); s.age = num(m, "age"); s.life = num(m, "life"); s.fuel = num(m, "fuel"); s.spent = num(m, "spent");
            s.shower = bool(m, "shower"); s.day = bool(m, "day"); s.gust = bool(m, "gust");
            storms.add(s);
        }
        gusts = new ArrayList<>();
        for (Object o : (List<Object>) head.get("gusts")) {
            var m = (Map<String, Object>) o; Gust g = new Gust();
            g.x = num(m, "x"); g.y = num(m, "y"); g.r = num(m, "r"); g.v0 = num(m, "v0"); g.str = num(m, "str"); g.age = num(m, "age"); g.sp = num(m, "sp"); g.kids = num(m, "kids"); g.fade = num(m, "fade");
            int si = (int) num(m, "s"); g.s = si >= 0 && si < storms.size() ? storms.get(si) : null;
            gusts.add(g);
        }
        highs = new ArrayList<>();
        for (Object o : (List<Object>) head.get("highs")) {
            var m = (Map<String, Object>) o; High Hh = new High();
            Hh.x = num(m, "x"); Hh.y = num(m, "y"); Hh.vx = num(m, "vx"); Hh.vy = num(m, "vy"); Hh.R = num(m, "R"); Hh.D = num(m, "D"); Hh.depth = num(m, "depth"); Hh.age = num(m, "age"); Hh.life = num(m, "life");
            highs.add(Hh);
        }
        lows = new ArrayList<>();
        for (Object o : (List<Object>) head.get("lows")) {
            var m = (Map<String, Object>) o; Low L = new Low();
            L.x = num(m, "x"); L.y = num(m, "y"); L.vx = num(m, "vx"); L.vy = num(m, "vy"); L.R = num(m, "R"); L.D = num(m, "D"); L.age = num(m, "age"); L.life = num(m, "life"); L.depth = 0;
            lows.add(L);
        }
        int o = 4 + hl, phN = (int) num(head, "phN");
        for (Object nmO : (List<Object>) head.get("arrays")) {
            String nm = (String) nmO;
            double[] src = new double[N]; for (int k = 0; k < N; k++) src[k] = d.getFloat(o + 4 * k); o += N * 4;
            if (nm.equals("PH0")) { PH.clear(); for (int i = 0; i < phN; i++) PH.add(src.clone()); }
            else { double[] dst = saved(nm); if (dst != null) System.arraycopy(src, 0, dst, 0, N); }
        }
        phT = num(head, "phT");
        updateClimate(); moveSystems(0); updateSystems(); sysT = simT; sunAt(simT, SUN); leeWaves(); waveT = simT; lowWind();
    }
    public record Advance(String mode, int days, int steps) {}
    // to time t after a skip: short gaps are stepped through at 60 s; long ones run the ground day by day on the
    // climate, then start the air fresh a few hours before t and spin it up
    public Advance advanceTo(double t) {
        double gap = t - simT; if (gap <= 0) return new Advance("none", 0, 0);
        if (gap <= P.catchUp * 3600) { int n = 0; while (simT < t - 1e-6) { step(Math.min(60, t - simT)); n++; } return new Advance("stepped", 0, n); }
        double spin = P.spinUp * 3600, ground0 = simT;
        for (double d = simT; d < t - spin; d += 86400) {
            double day = Math.min(86400, t - spin - d); simT = d + day / 2; updateClimate();
            MonthPos mp = monthPos(simT);
            for (int k = 0; k < N; k++) {
                if (SEA[k] != 0) continue;
                double pr = IP[k] * (MSH[mp.m0 * N + k] * (1 - mp.f) + MSH[mp.m1 * N + k] * mp.f) * 12 / (yearLen(calendar(simT).y) / 86400 * 86400) * day, tg = TAIR[k];
                if (tg < P.snowT) SNOW[k] = (float) (SNOW[k] + pr); else SOIL[k] = (float) clamp01(SOIL[k] + pr / 40);
                if (tg > 0 && SNOW[k] > 0) { double m = Math.min(SNOW[k], P.melt * tg * day / 86400); SNOW[k] = (float) (SNOW[k] - m); SOIL[k] = (float) clamp01(SOIL[k] + m / 40); }
                SOIL[k] = (float) clamp01(SOIL[k] - P.landEvap * Math.min(TAIR[k] / 22.0, 1.3) * day / 86400 / 40 * SOIL[k]);
            }
        }
        double[] snow = SNOW.clone(), soil = SOIL.clone();
        simT = t - spin; storms = new ArrayList<>(); gusts = new ArrayList<>(); lows = new ArrayList<>(); highs = new ArrayList<>(); PH.clear(); phT = -1e12; climT = -1e12; waveT = -1e12; sysT = -1e12;
        updateClimate(); System.arraycopy(TAIR, 0, TG, 0, N); updateSystems();
        for (int k = 0; k < N; k++) { Q[k] = (float) (rhNow() * qsat(k) * QM[k] * pow(QBIAS[k], 0.6)); CS[k] = CC[k] = A[k] = 0; ANV[k] = 0; }
        System.arraycopy(snow, 0, SNOW, 0, N); System.arraycopy(soil, 0, SOIL, 0, N); nextFront = simT + frontGapS() * rnd();
        int n = 0; while (simT < t - 1e-6) { step(Math.min(60, t - simT)); n++; }
        return new Advance("fresh air", (int) Math.round((t - spin - ground0) / 86400), n);
    }

    // ---- the module's face ----
    public void seed(int n) { rngS = n; calSeed = n; calY = 0; calT0 = 0; }
    public void setSpell(boolean wet, double days) {
        spellWet = wet; spellUntil = simT + (days != 0 ? days : wet ? P.wetSpell : P.drySpell) * 86400; spellHum = seasonGain(simT) * (wet ? 1 + P.spell : 1);
    }
    public boolean spellWet() { return spellWet; }
    public double spellUntil() { return spellUntil; }
    public double spellHum() { return spellHum; }
    public void setTime(double t) {
        simT = t; spellUntil = 0; spellWet = rnd() < P.drySpell / (P.wetSpell + P.drySpell); spellHum = seasonGain(t);
        sysT = -1e12; PH.clear(); phT = -1e12; climT = -1e12; waveT = -1e12;
        storms = new ArrayList<>(); gusts = new ArrayList<>(); lows = new ArrayList<>(); highs = new ArrayList<>();
        updateClimate(); initAir(); nextFront = simT + frontGapS() * rnd();
    }
    public double time() { return simT; }
    public void resetTotals() { java.util.Arrays.fill(ACC, 0); java.util.Arrays.fill(MACC, 0); java.util.Arrays.fill(MSEC, 0); accT = 0; }
    public double[] rainTotalMm() { return ACC; }
    public double[] monthTotalMm() { return MACC; }
    public double[] monthSeconds() { return MSEC; }
    public double totalSeconds() { return accT; }
    public void sendLow() { sendFront(); sysT = -1e12; }
    public List<Low> lows() { return lows; }
    public List<High> highs() { return highs; }
    public List<Storm> storms() { return storms; }
    public double[] bakedPrecipMm() { return IP; }
    public double[] bakedShare() { return MSH; }
    public byte[] sea() { return SEA; }
    public double[] heightM() { return H; }
    public double[] latitude() { return LAT; }
    public double[] longitude() { return LON; }
    public double meanLandLatitude() { return LATC; }
    public void autoFronts(boolean on) { autoFronts = on; }

    // The fields a host may read (live channels, by registry key: rider.json "writes"). The arrays are live: they change
    // on the next step (copy what another thread reads).
    public static final String[] FIELDS = { "vapour_mm", "cloud_layered", "cloud_heaped", "rain_mm_h", "snow_mm", "cover_layered", "cover_heaped",
            "base_layered_m", "top_layered_m", "base_heaped_m", "top_heaped_m", "cirrus", "anvil", "lens", "fog", "dust", "wind_low_e", "wind_low_s",
            "gustiness", "pressure_hpa", "pressure_change_3h_hpa", "gust_front", "cold_pool", "temp_ground_c" };
    public double[] field(String key) {
        return switch (key) {
            case "vapour_mm" -> Q; case "cloud_layered" -> CS; case "cloud_heaped" -> CC; case "rain_mm_h" -> RAIN; case "snow_mm" -> SNOW;
            case "cover_layered" -> COVS; case "cover_heaped" -> COVC; case "base_layered_m" -> BASES; case "top_layered_m" -> TOPS;
            case "base_heaped_m" -> BASEC; case "top_heaped_m" -> TOPC; case "cirrus" -> HIGH; case "anvil" -> ANVC; case "lens" -> LENS;
            case "fog" -> FOG; case "dust" -> DUST; case "wind_low_e" -> LOWU; case "wind_low_s" -> LOWV; case "gustiness" -> GUSTY;
            case "pressure_hpa" -> PP; case "pressure_change_3h_hpa" -> PTEND; case "gust_front" -> GEDGE; case "cold_pool" -> COLD; case "temp_ground_c" -> TG;
            // beyond the lab's list: the soil's water (0..1) and whether it snows
            case "soil_wet" -> SOIL; case "snowing" -> SNOWF;
            default -> throw new IllegalArgumentException("no weather field " + key);
        };
    }
    public Map<String, double[]> fields() { var m = new LinkedHashMap<String, double[]>(); for (String k : FIELDS) m.put(k, field(k)); return m; }
}
