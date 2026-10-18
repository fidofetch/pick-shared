package com.pick.voxel.debug;

import imgui.ImGui;
import imgui.ImGuiStyle;
import imgui.flag.ImGuiCol;

// Dark walnut + one amber accent. Applied once at HUD init; warm browns on purpose: rustic, not clinical.
public final class Theme {
    private Theme() {}

    public static final float[] ACCENT = {0.894f, 0.659f, 0.294f, 1f};
    public static final float[] WARN   = {0.82f, 0.30f, 0.24f, 1f};   // fills only: as text it is 4.2:1 on BG
    // Warnings as text: 6.7:1 on BG, 5.1:1 on a field (WCAG 4.5:1 for text); also a fill under dark text (6.4:1)
    public static final float[] WARN_TEXT = {0.941f, 0.478f, 0.384f, 1f};

    private static final float[] BG       = {0.098f, 0.075f, 0.059f, 0.93f};
    private static final float[] SURFACE  = {0.149f, 0.114f, 0.086f, 1f};
    private static final float[] SURFACE2 = {0.208f, 0.161f, 0.122f, 1f};
    private static final float[] BORDER   = {0.290f, 0.227f, 0.165f, 1f};   // separators: quiet on purpose
    // Edges that mean something (an input field, a window): 3.2:1 on BG, the 3:1 asked of meaningful outlines
    private static final float[] EDGE     = {0.478f, 0.384f, 0.286f, 1f};
    private static final float[] TEXT     = {0.925f, 0.882f, 0.804f, 1f};
    private static final float[] MUTED    = {0.659f, 0.592f, 0.498f, 1f};
    private static final float[] ON_ACCENT = {0.110f, 0.071f, 0.031f, 1f};
    // For drawing your own shapes and text in the theme's colours (docs/design-language.md): copies, so the theme stays put
    public static final float[] TEXT_RGB = TEXT.clone(), MUTED_RGB = MUTED.clone(), EDGE_RGB = EDGE.clone();

    public static void apply() {
        ImGuiStyle s = ImGui.getStyle();
        // Tooltips wait until the mouse rests on an item (passing over the panel shows none), then a moment more
        s.setHoverFlagsForTooltipMouse(imgui.flag.ImGuiHoveredFlags.Stationary | imgui.flag.ImGuiHoveredFlags.DelayNormal | imgui.flag.ImGuiHoveredFlags.AllowWhenDisabled);
        s.setHoverStationaryDelay(0.35f);
        s.setHoverDelayNormal(0.8f);

        s.setWindowPadding(14, 12);
        s.setFramePadding(8, 5);
        s.setItemSpacing(8, 8);
        s.setItemInnerSpacing(8, 6);
        s.setWindowRounding(10);
        s.setFrameRounding(6);
        s.setGrabRounding(8);
        s.setTabRounding(6);
        s.setPopupRounding(6);
        s.setScrollbarRounding(6);
        s.setWindowBorderSize(1);
        s.setFrameBorderSize(1);   // fields show where they are without hovering (#211)
        s.setGrabMinSize(14);
        s.setScrollbarSize(10);
        s.setWindowTitleAlign(0.02f, 0.5f);

        col(ImGuiCol.Text, TEXT);
        col(ImGuiCol.TextDisabled, MUTED);
        col(ImGuiCol.WindowBg, BG);
        col(ImGuiCol.PopupBg, SURFACE);
        col(ImGuiCol.Border, EDGE);
        col(ImGuiCol.Separator, BORDER);
        col(ImGuiCol.TitleBg, SURFACE);
        col(ImGuiCol.TitleBgActive, SURFACE);
        col(ImGuiCol.TitleBgCollapsed, SURFACE);
        col(ImGuiCol.FrameBg, SURFACE2);
        col(ImGuiCol.FrameBgHovered, 0.263f, 0.204f, 0.153f, 1f);
        col(ImGuiCol.FrameBgActive, 0.318f, 0.247f, 0.184f, 1f);
        col(ImGuiCol.CheckMark, ACCENT);
        col(ImGuiCol.SliderGrab, ACCENT);
        col(ImGuiCol.SliderGrabActive, 0.961f, 0.769f, 0.435f, 1f);
        col(ImGuiCol.Button, SURFACE2);
        col(ImGuiCol.ButtonHovered, 0.263f, 0.204f, 0.153f, 1f);
        col(ImGuiCol.ButtonActive, 0.318f, 0.247f, 0.184f, 1f);   // pressed: darker, so its light text stays readable (on accent it was 1.6:1)
        col(ImGuiCol.Header, SURFACE);
        col(ImGuiCol.HeaderHovered, SURFACE2);
        col(ImGuiCol.HeaderActive, SURFACE2);
        col(ImGuiCol.Tab, SURFACE);
        col(ImGuiCol.TabHovered, SURFACE2);
        col(ImGuiCol.TabSelected, ACCENT);
        col(ImGuiCol.TabDimmed, SURFACE);
        col(ImGuiCol.TabDimmedSelected, SURFACE2);
        col(ImGuiCol.PlotLines, ACCENT);
        col(ImGuiCol.PlotLinesHovered, 1f, 1f, 1f, 1f);
        col(ImGuiCol.PlotHistogram, ACCENT);
        col(ImGuiCol.ScrollbarBg, 0f, 0f, 0f, 0f);
        col(ImGuiCol.ScrollbarGrab, BORDER);
        col(ImGuiCol.ScrollbarGrabHovered, 0.380f, 0.298f, 0.220f, 1f);
        col(ImGuiCol.ModalWindowDimBg, 0f, 0f, 0f, 0.6f);   // behind a dialog: the screen goes dark, not grey
        col(ImGuiCol.NavCursor, ACCENT);   // the keyboard focus ring: accent, so it is seen (8.8:1 on BG)
    }

    // Text color for content drawn on an accent-colored fill (selected tab)
    public static float[] onAccent() { return ON_ACCENT; }

    // A button that stands out (the main action): accent fill, dark text. Pair with popPrimary().
    public static void pushPrimary() {
        ImGui.pushStyleColor(ImGuiCol.Button, ACCENT[0], ACCENT[1], ACCENT[2], 1f);
        ImGui.pushStyleColor(ImGuiCol.ButtonHovered, Math.min(1, ACCENT[0] * 1.08f), Math.min(1, ACCENT[1] * 1.12f), Math.min(1, ACCENT[2] * 1.2f), 1f);
        ImGui.pushStyleColor(ImGuiCol.ButtonActive, ACCENT[0] * 0.85f, ACCENT[1] * 0.85f, ACCENT[2] * 0.85f, 1f);
        ImGui.pushStyleColor(ImGuiCol.Text, ON_ACCENT[0], ON_ACCENT[1], ON_ACCENT[2], 1f);
        ImGui.pushStyleColor(ImGuiCol.Border, ACCENT[0], ACCENT[1], ACCENT[2], 1f);   // its edge is its fill
    }
    public static void popPrimary() { ImGui.popStyleColor(5); }

    // A secondary button that is still easy to find: a lighter fill and an accent edge. Pair with popOutlined().
    public static void pushOutlined() {
        ImGui.pushStyleVar(imgui.flag.ImGuiStyleVar.FrameBorderSize, 1.5f);
        ImGui.pushStyleColor(ImGuiCol.Border, ACCENT[0], ACCENT[1], ACCENT[2], 0.75f);
        ImGui.pushStyleColor(ImGuiCol.Button, SURFACE2[0] * 1.25f, SURFACE2[1] * 1.25f, SURFACE2[2] * 1.25f, 1f);
        ImGui.pushStyleColor(ImGuiCol.Text, TEXT[0], TEXT[1], TEXT[2], 1f);
    }
    public static void popOutlined() { ImGui.popStyleColor(3); ImGui.popStyleVar(); }

    private static void col(int idx, float[] c) { ImGui.getStyle().setColor(idx, c[0], c[1], c[2], c[3]); }
    private static void col(int idx, float r, float g, float b, float a) { ImGui.getStyle().setColor(idx, r, g, b, a); }
}
