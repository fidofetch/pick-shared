package com.pick.settings;

import com.pick.voxel.debug.Theme;
import com.pick.voxel.debug.UiFonts;
import imgui.ImGui;

import java.util.ArrayList;
import java.util.List;

// The Settings page: the sections modules added, in order, each under its heading; then the app's own
// footer (an "Advanced" button, say), Back, and a note that changes are kept. The file is written half a
// second after the last change and on Back, so dragging a slider writes once.
//     page.add(new DisplaySection(...));      at start, then page.restore() once
//     if (page.draw()) ...                    each frame it is shown; true when Back was pressed
//     page.set("sky.haze", "0.3")             the console's set: writes the key, then the section re-reads it
public final class SettingsPage {
    private final SettingsFile file;
    private final List<SettingsSection> sections = new ArrayList<>();
    private Runnable footer = () -> { };
    private String saved, pending = "";
    private double dirtyAt = -1;

    public SettingsPage(SettingsFile file) { this.file = file; }

    public <S extends SettingsSection> S add(S s) {
        for (SettingsSection o : sections)
            if (o.key().equals(s.key())) throw new IllegalArgumentException("two settings sections use \"" + s.key() + "\"");
        sections.add(s);
        return s;
    }
    public List<SettingsSection> sections() { return List.copyOf(sections); }
    public SettingsFile file() { return file; }
    public void setFooter(Runnable r) { footer = r; }

    // Every section puts back what was saved (call once, after the app has set its defaults)
    public void restore() {
        for (SettingsSection s : sections) s.restore(file.scope(s.key()));
        storeAll();
        saved = file.snapshot();
    }

    public boolean draw() {
        for (SettingsSection s : sections) {
            ImGui.spacing();
            ImGui.textColored(Theme.ACCENT[0], Theme.ACCENT[1], Theme.ACCENT[2], 1f, s.title().toUpperCase());
            ImGui.separator();
            ImGui.pushID(s.key());
            s.draw();
            ImGui.popID();
        }
        ImGui.spacing();
        footer.run();
        ImGui.spacing();
        boolean back = ImGui.button("Back", ImGui.getContentRegionAvailX(), UiFonts.px(32));
        ImGui.textDisabled("Changes are saved for next time.");
        saveWhenSettled(back);
        return back;
    }

    // Saves now if anything changed (also used when a setting is changed off the page, by the console)
    public void saveIfChanged() { saveWhenSettled(true); }

    private void storeAll() { for (SettingsSection s : sections) s.store(file.scope(s.key())); }

    private void saveWhenSettled(boolean now) {
        storeAll();
        String st = file.snapshot();
        if (saved == null) saved = st;
        if (!st.equals(saved)) {
            if (dirtyAt < 0 || !st.equals(pending)) { dirtyAt = ImGui.getTime(); pending = st; }
            if (now || ImGui.getTime() - dirtyAt > 0.5) { file.save(); saved = st; dirtyAt = -1; }
        } else dirtyAt = -1;
    }

    // The console's "set": writes key ("section.name") and lets that section re-read it, so the value is
    // checked and clamped the same way as at start. Returns the value the section kept.
    public String set(String key, String value) {
        SettingsSection s = owner(key);
        file.scope(s.key()).put(key.substring(s.key().length() + 1), value);
        s.restore(file.scope(s.key()));
        storeAll();
        String kept = file.all().get(key);
        file.save();
        saved = file.snapshot();
        return kept;
    }

    private SettingsSection owner(String key) {
        for (SettingsSection s : sections) if (key.startsWith(s.key() + ".")) return s;
        StringBuilder b = new StringBuilder();
        for (SettingsSection s : sections) b.append(b.length() > 0 ? ", " : "").append(s.key());
        throw new IllegalArgumentException("no settings section for \"" + key + "\" (the sections are " + b + ")");
    }
}
