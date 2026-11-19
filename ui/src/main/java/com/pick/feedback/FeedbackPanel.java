package com.pick.feedback;

import com.pick.voxel.debug.Theme;
import imgui.ImGui;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiKey;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImString;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.function.Supplier;

// Feedback from any screen: the "Feedback (F9)" chip at the top centre, or F9. It takes a picture of the app
// first (without the panel), then opens a small panel: a category, a few words, a tick to keep the picture.
// Send saves the note at once (FeedbackNotes). Works in any app with an ImGui frame:
//     panel.draw(winW, winH)       each frame, between ImGui.newFrame() and ImGui.render()
//     panel.takeShotRequest()      after drawing: when not null, save the frame there as a png
public final class FeedbackPanel {
    private static final float WIDTH = 430;

    private final FeedbackNotes notes;
    private final Supplier<Map<String, Object>> state;
    private boolean showChip = true;
    private float minX, maxX;   // this frame, the chip stays between these (window x); 0: no limit

    // stage 1 = asked, 2 = the app is taking the picture (nothing of ours drawn), 3 = panel open
    private int stage;
    private LocalDateTime when;
    private Path shot;
    private volatile Path shotRequest;
    private boolean shotOk, attach;
    private int category;
    private final ImString text = new ImString(2000);
    private String done = "";
    private double doneAt = -1;
    private Map<String, Object> stateAtPress;
    private double fpsAtPress;

    private static volatile boolean open;

    // True while the panel is open: Esc closes it, so the app should not also treat Esc as "go back"
    public static boolean isOpen() { return open; }

    public FeedbackPanel(FeedbackNotes notes, Supplier<Map<String, Object>> state) { this.notes = notes; this.state = state; }

    // false: no chip (F9 still works), for screens where nothing may sit at the top centre
    public void showChip(boolean show) { showChip = show; }

    // Open it as if F9 was pressed (a dev flag, or a menu entry)
    public void request() { if (stage == 0) stage = 1; }

    // The chip is centred, but stays right of x (call each frame before draw: the end of the app's own top
    // bar buttons), so large text never puts it over them
    public void keepRightOf(float x) { minX = x; }
    // ... and left of maxX too (where the bar's right-hand buttons start)
    public void keepWithin(float x, float maxX) { minX = x; this.maxX = maxX; }

    // How wide the chip is, for an app laying out its top bar around it
    public static float chipWidth() {
        var st = ImGui.getStyle();
        return ImGui.calcTextSize(t("chip")).x + 2 * st.getFramePaddingX() + 2 * st.getWindowPaddingX();
    }

    public Path takeShotRequest() { Path p = shotRequest; shotRequest = null; return p; }

    public void draw(float winW, float winH) {
        if (winW < 100 || winH < 100) return;   // the first frame reports no display size yet
        open = stage == 3;
        if (stage == 1) {   // draw nothing this frame; the app saves the picture when the frame is done
            when = LocalDateTime.now();
            stateAtPress = safeState();
            fpsAtPress = ImGui.getIO().getFramerate();
            shot = notes.shotPath(when);
            try { Files.createDirectories(shot.getParent()); } catch (java.io.IOException ignored) { }
            shotRequest = shot;
            stage = 2;
            return;
        }
        if (stage == 2) { shotOk = Files.exists(shot); attach = shotOk; stage = 3; }
        if (stage == 0 && !ImGui.getIO().getWantTextInput() && ImGui.isKeyPressed(ImGuiKey.F9)) { stage = 1; return; }
        if (stage == 3) { panel(winW, winH); return; }
        if (showChip) chip(winW);
    }

    private void chip(float winW) {
        float w = chipWidth(), x = winW / 2 - w / 2;
        if (maxX > 0) x = Math.min(x, maxX - 12 - w);
        if (minX > 0) x = Math.max(x, minX + 12);
        ImGui.setNextWindowPos(x, 9, ImGuiCond.Always);
        minX = maxX = 0;
        ImGui.setNextWindowBgAlpha(0.7f);
        ImGui.begin("##feedbackchip", ImGuiWindowFlags.NoTitleBar | ImGuiWindowFlags.AlwaysAutoResize | ImGuiWindowFlags.NoSavedSettings
                | ImGuiWindowFlags.NoMove | ImGuiWindowFlags.NoFocusOnAppearing);
        boolean recent = doneAt >= 0 && ImGui.getTime() - doneAt < 4;   // "sent" shows for a few seconds, then the chip is plain again
        if (ImGui.smallButton((recent ? done : t("chip")) + "##fb")) stage = 1;
        if (ImGui.isItemHovered(imgui.flag.ImGuiHoveredFlags.ForTooltip))
            ImGui.setTooltip(t("chip.tip"));
        ImGui.end();
    }

    private void panel(float winW, float winH) {
        ImGui.setNextWindowPos(winW / 2, 48, ImGuiCond.Always, 0.5f, 0f);   // under the chip, where the eye already is
        ImGui.setNextWindowSizeConstraints(WIDTH, 0, WIDTH, winH - 60);
        ImGui.begin("##feedback", ImGuiWindowFlags.NoTitleBar | ImGuiWindowFlags.AlwaysAutoResize | ImGuiWindowFlags.NoSavedSettings);
        ImGui.pushTextWrapPos(WIDTH - 24);
        ImGui.textColored(Theme.ACCENT[0], Theme.ACCENT[1], Theme.ACCENT[2], 1f, t("title"));
        ImGui.textDisabled(t("about"));
        ImGui.spacing();
        ImGui.setNextItemWidth(WIDTH - 24);
        if (ImGui.beginCombo("##fbcat", categoryName(category))) {
            for (int i = 0; i < FeedbackNotes.CATEGORIES.size(); i++)
                if (ImGui.selectable(categoryName(i) + "##cat" + i, i == category)) category = i;
            ImGui.endCombo();
        }
        if (ImGui.isWindowAppearing()) ImGui.setKeyboardFocusHere();
        ImGui.inputTextMultiline("##fbtext", text, WIDTH - 24, 110);
        if (shotOk) {
            if (ImGui.checkbox(t("attach") + "##attach", attach)) attach = !attach;
            if (ImGui.isItemHovered(imgui.flag.ImGuiHoveredFlags.ForTooltip)) ImGui.setTooltip(t("attach.tip"));
        } else ImGui.textDisabled(t("no_shot"));
        ImGui.spacing();
        boolean empty = text.get().isBlank();
        ImGui.beginDisabled(empty);
        Theme.pushPrimary();
        boolean send = ImGui.button(t("send") + "##send");
        Theme.popPrimary();
        ImGui.endDisabled();
        if (empty && ImGui.isItemHovered(imgui.flag.ImGuiHoveredFlags.AllowWhenDisabled)) ImGui.setTooltip(t("send.empty"));
        if (send) {
            String cat = FeedbackNotes.CATEGORIES.get(category);
            boolean keep = shotOk && attach;
            try {
                notes.write(when, cat, text.get(), keep ? shot : null, stateAtPress, fpsAtPress);
                done = t("sent", "category", categoryName(category).toLowerCase(java.util.Locale.ROOT));
            } catch (java.io.IOException e) { done = t("not_saved", "reason", e.getMessage()); }
            if (!keep) discardShot();
            doneAt = ImGui.getTime();
            close();
        }
        ImGui.sameLine();
        if (ImGui.button(t("cancel") + "##cancel") || ImGui.isKeyPressed(ImGuiKey.Escape)) { discardShot(); close(); }
        ImGui.textDisabled(t("saved_in", "path", notes.dir().toAbsolutePath()).replace("%", "%%"));
        ImGui.popTextWrapPos();
        ImGui.end();
    }

    // The panel's words: shared.feedback.* in lang/<code>/shared.txt. The note on disk keeps the English category.
    private static String t(String key, Object... fields) { return com.pick.text.Text.get("shared.feedback." + key, fields); }
    private static String categoryName(int i) { return t("category." + i); }

    private Map<String, Object> safeState() {
        try { return state == null ? Map.of() : new java.util.LinkedHashMap<>(state.get()); }
        catch (RuntimeException e) { return Map.of("error", String.valueOf(e.getMessage())); }
    }

    private void discardShot() {
        try { if (shot != null) { Files.deleteIfExists(shot); Files.deleteIfExists(FeedbackNotes.cleanOf(shot)); } } catch (java.io.IOException ignored) { }
    }

    private void close() {
        stage = 0;
        open = false;
        text.set("");
    }
}
