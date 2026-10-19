package com.pick.voxel.debug;

import imgui.ImGui;

// Thin helpers over ImGui so panels can edit plain fields directly:
//     rs.shadowOn = Ui.toggle("Shadow", rs.shadowOn);
//     env.shadowK = Ui.slider("Shadow K", env.shadowK, 1, 32);
// instead of mirroring every value into ImBoolean / float[] holders and copying back.
// Main thread only (shared scratch arrays).
public final class Ui {
    private Ui() {}

    private static final float[] F = new float[1];
    private static final int[] I = new int[1];

    public static boolean toggle(String label, boolean v) {
        return ImGui.checkbox(label, v) ? !v : v;   // checkbox returns true when clicked
    }

    public static float slider(String label, float v, float min, float max) {
        F[0] = v;
        fieldWidth();
        ImGui.sliderFloat(label, F, min, max);
        return F[0];
    }

    public static float slider(String label, float v, float min, float max, String format) {
        F[0] = v;
        fieldWidth();
        ImGui.sliderFloat(label, F, min, max, format);
        return F[0];
    }

    public static int slider(String label, int v, int min, int max) {
        I[0] = v;
        fieldWidth();
        ImGui.sliderInt(label, I, min, max);
        return I[0];
    }

    public static int slider(String label, int v, int min, int max, String format) {
        I[0] = v;
        ImGui.sliderInt(label, I, min, max, format);
        return I[0];
    }
    // A slider whose track is painted with what it does: a gradient through `stops` (rgb 0..1).
    // The frame is made transparent so only the painted track and the grab show. Full width.
    public static float gradientSlider(String id, float v, float min, float max, float[]... stops) {
        float x = ImGui.getCursorScreenPosX(), y = ImGui.getCursorScreenPosY();
        float w = ImGui.getContentRegionAvailX(), h = ImGui.getFrameHeight();
        float ty = y + h / 2 - 4;
        var dl = ImGui.getWindowDrawList();
        int n = stops.length - 1;
        for (int i = 0; i < n; i++) {
            int c0 = pack(stops[i]), c1 = pack(stops[i + 1]);
            dl.addRectFilledMultiColor(x + w * i / n, ty, x + w * (i + 1) / n, ty + 8, c0, c1, c1, c0);
        }
        dl.addRect(x, ty, x + w, ty + 8, ImGui.getColorU32(imgui.flag.ImGuiCol.Border));
        ImGui.pushStyleColor(imgui.flag.ImGuiCol.FrameBg, 0f, 0f, 0f, 0f);
        ImGui.pushStyleColor(imgui.flag.ImGuiCol.FrameBgHovered, 0f, 0f, 0f, 0f);
        ImGui.pushStyleColor(imgui.flag.ImGuiCol.FrameBgActive, 0f, 0f, 0f, 0f);
        F[0] = v;
        ImGui.setNextItemWidth(w);
        ImGui.sliderFloat("##" + id, F, min, max, " ");
        ImGui.popStyleColor(3);
        return F[0];
    }

    public static int pack(float[] rgb) { return pack(rgb[0], rgb[1], rgb[2], 1f); }

    // ImGui's packed color: 0xAABBGGRR
    public static int pack(float r, float g, float b, float a) {
        return (Math.round(clamp01(a) * 255) << 24) | (Math.round(clamp01(b) * 255) << 16)
                | (Math.round(clamp01(g) * 255) << 8) | Math.round(clamp01(r) * 255);
    }

    // Sliders take the full row minus room for their label, so values line up across a panel
    private static void fieldWidth() { ImGui.setNextItemWidth(-150); }

    // ImGui.text / setTooltip treat their string as a printf format, so an already-
    // formatted "1% low" would be re-interpreted ("% l" -> garbage). Never pass them
    // pre-formatted text directly: use these.

    // Tooltip for the widget drawn just before this call, after a rest (Theme sets the delay). The text is shown
    // as it is (no printf formatting) and wraps at about 70 characters, so a long tip stays on screen at 200%
    public static void tip(String text) {
        if (!ImGui.isItemHovered(imgui.flag.ImGuiHoveredFlags.ForTooltip)) return;
        ImGui.beginTooltip();
        ImGui.pushTextWrapPos(UiFonts.px(460));
        ImGui.textUnformatted(text);
        ImGui.popTextWrapPos();
        ImGui.endTooltip();
    }

    public static void text(String format, Object... args) {
        ImGui.textUnformatted(String.format(format, args));
    }

    // Blue -> cyan -> green -> yellow -> red for x in [0, 1]. Same ramp as heat() in quad.frag,
    // so CPU-drawn overlays and shader debug views share one color language.
    public static float[] heat(float x) {
        x = Math.max(0f, Math.min(1f, x));
        return new float[]{
                clamp01(1.5f - Math.abs(4f * x - 3f)),
                clamp01(1.5f - Math.abs(4f * x - 2f)),
                clamp01(1.5f - Math.abs(4f * x - 1f)) };
    }

    private static float clamp01(float v) { return Math.max(0f, Math.min(1f, v)); }
}
