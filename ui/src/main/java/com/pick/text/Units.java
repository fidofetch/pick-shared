package com.pick.text;

import java.math.BigDecimal;
import java.math.RoundingMode;

// Sizes for the player in metric with imperial beside it, worded by lang/<code>/units.txt so a language can change the
// order or the unit names:  Units.distance(15) = "15 m (49 ft)",  Units.distance(1000) = "1 km (0.6 mi)".
public final class Units {
    private Units() {}

    /** A distance in metres: under 1 km in m (ft), from 1 km in km (mi). */
    public static String distance(double m) {
        if (Math.abs(m) < 1000) return Text.get("units.m", "m", num(round(m, m < 10 ? 1 : 0)), "ft", num(round(m * 3.28084, m < 10 ? 1 : 0)));
        return Text.get("units.km", "km", num(round(m / 1000, 1)), "mi", num(round(m / 1609.344, 1)));
    }

    /** A length in centimetres with inches, for things in the hand: "55 cm (22 in)". */
    public static String length(double m) {
        return Text.get("units.cm", "cm", num(round(m * 100, 0)), "in", num(round(m * 39.3701, 0)));
    }

    /** A mass in kg with lb: "2.5 kg (5.5 lb)". */
    public static String mass(double kg) {
        return Text.get("units.kg", "kg", num(round(kg, kg < 10 ? 1 : 0)), "lb", num(round(kg * 2.20462, kg < 10 ? 1 : 0)));
    }

    /** A number as people write it: no trailing zeros (2, 0.6, 12.5), a dot for the decimal point for now. */
    public static String num(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return String.valueOf(v);
        String s = BigDecimal.valueOf(v).stripTrailingZeros().toPlainString();
        return s.equals("-0") ? "0" : s;
    }

    private static double round(double v, int places) {
        return BigDecimal.valueOf(v).setScale(places, RoundingMode.HALF_UP).doubleValue();
    }
}
