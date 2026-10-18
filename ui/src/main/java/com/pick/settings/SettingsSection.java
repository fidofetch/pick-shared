package com.pick.settings;

// One module's part of the Settings page: its heading, its controls, and its keys in the settings file
// (under key() + "."). A module adds its section to the SettingsPage, the way it adds a panel to the debug HUD;
// it never reads or writes another module's keys. Player choices go here; raw tuning stays in the HUD.
public interface SettingsSection {
    String title();   // the group heading, e.g. "Sky"
    String key();     // the prefix in the file, e.g. "sky"

    // Put back what was saved (at start, and after the console's "set"). Keep the module's own value for a
    // key that is missing or out of range.
    void restore(SettingsFile.Scope s);

    // Write the current values. Called each frame on the Settings page to notice changes, so keep it cheap
    // and write only what the player chose (a value the player never touched may stay out of the file).
    void store(SettingsFile.Scope s);

    void draw();
}
