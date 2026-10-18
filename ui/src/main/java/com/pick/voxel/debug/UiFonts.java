package com.pick.voxel.debug;

import imgui.ImFontConfig;
import imgui.ImGui;
import imgui.ImGuiIO;

// The interface's lettering: IBM Plex Sans (SIL OFL, ui/fonts/) at a size people can read, instead of ImGui's
// 13 px pixel font. Text should be at least 18 px tall on a 1080p PC screen (Xbox Accessibility Guideline 101):
// BASE is the font size in ImGui's sense, about the height from descender to ascender.
//     UiFonts.load(io)                         once, before the first frame
//     UiFonts.scale(contentScale, textScale)   whenever the monitor or the Text size setting changes
// ImGui 1.92 draws fonts at any size, so changing the scale needs no rebuild.
public final class UiFonts {
    private UiFonts() {}

    public static final float BASE = 17f;
    public static final float[] TEXT_SCALES = {1f, 1.25f, 1.5f, 2f};
    public static final String[] TEXT_SCALE_NAMES = {"Normal (100%)", "Large (125%)", "Larger (150%)", "Largest (200%)"};
    private static float sizesScaled = 1f, k = 1f, dpi = 1f;

    // Layout sizes written for Normal text on a plain monitor: px(380) is 380 there and grows with the text,
    // so buttons and panels keep fitting their labels at any Text size
    public static float px(float v) { return v * k; }
    // The same, following the monitor only (not the Text size): for things that should not grow with text
    public static float dpx(float v) { return v * dpi; }

    // Loads the UI font; false (and ImGui's own font) if it is missing from the jar
    public static boolean load(ImGuiIO io) {
        try (var in = UiFonts.class.getResourceAsStream("/ui/fonts/IBMPlexSans-Variable.ttf")) {
            if (in == null) { io.getFonts().addFontDefault(); return false; }
            // From a file, not memory: ImGui 1.92 draws glyphs on demand, so it must keep the font data for the
            // whole run, and a Java array handed over from memory is not kept (the text came out blank)
            java.nio.file.Path f = java.nio.file.Files.createTempFile("ui-font-", ".ttf");
            f.toFile().deleteOnExit();
            java.nio.file.Files.write(f, in.readAllBytes());
            ImFontConfig cfg = new ImFontConfig();
            cfg.setName("IBM Plex Sans");
            io.getFonts().addFontFromFileTTF(f.toString(), BASE, cfg);
            cfg.destroy();
            return true;
        } catch (java.io.IOException e) {
            io.getFonts().addFontDefault();
            return false;
        }
    }

    // contentScale: the monitor's scale beyond what the framebuffer already gives (Windows at 150% says 1.5;
    // a Retina Mac says 1, its framebuffer is already twice the window). textScale: the Text size setting.
    // Paddings, spacing and rounding follow the monitor; the text follows both.
    public static void scale(float contentScale, float textScale) {
        var st = ImGui.getStyle();
        float c = Math.max(0.5f, contentScale);
        if (Math.abs(c - sizesScaled) > 1e-3f) { st.scaleAllSizes(c / sizesScaled); sizesScaled = c; }
        st.setFontScaleDpi(c);
        st.setFontScaleMain(textScale);
        dpi = c;
        k = c * textScale;
    }

    // The monitor's scale beyond what the framebuffer already gives, for scale(): GLFW's content scale divided
    // by the framebuffer-to-window ratio (Windows at 150% gives 1.5; a Retina Mac 1)
    public static float contentScale(long window) {
        float[] cx = {1}, cy = {1};
        org.lwjgl.glfw.GLFW.glfwGetWindowContentScale(window, cx, cy);
        int[] fw = {0}, fh = {0}, ww = {0}, wh = {0};
        org.lwjgl.glfw.GLFW.glfwGetFramebufferSize(window, fw, fh);
        org.lwjgl.glfw.GLFW.glfwGetWindowSize(window, ww, wh);
        float fbScale = ww[0] > 0 ? (float) fw[0] / ww[0] : 1f;
        return Math.max(0.5f, cx[0] / Math.max(1f, fbScale));
    }

    public static int textScaleIndex(float s) {
        for (int i = 0; i < TEXT_SCALES.length; i++) if (Math.abs(TEXT_SCALES[i] - s) < 1e-3f) return i;
        return 0;
    }
}
