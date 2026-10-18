package com.pick.settings;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SettingsTest {
    // A module with one clamped number and one switch
    static final class Fog implements SettingsSection {
        float density = 0.2f; boolean on = true;
        public String title() { return "Fog"; }
        public String key() { return "fog"; }
        public void restore(SettingsFile.Scope s) { density = s.f("density", density, 0f, 1f); on = s.b("on", on); }
        public void store(SettingsFile.Scope s) { s.put("density", density); s.put("on", on); }
        public void draw() { }
    }

    @Test void sectionsKeepTheirOwnKeys(@TempDir Path dir) {
        Path f = dir.resolve("s.properties");
        var page = new SettingsPage(SettingsFile.load(f, "test"));
        Fog fog = page.add(new Fog());
        page.restore();
        assertEquals("0.9", page.set("fog.density", "0.9"));
        assertEquals(0.9f, fog.density);
        assertEquals("1.0", page.set("fog.density", "7"), "clamped by the section");
        assertEquals("false", page.set("fog.on", "maybe"), "anything but true reads as false");
        var again = new SettingsPage(SettingsFile.load(f, "test"));
        Fog fog2 = again.add(new Fog());
        again.restore();
        assertEquals(1.0f, fog2.density, "saved and read back");
        assertThrows(IllegalArgumentException.class, () -> again.set("rain.amount", "1"));
        assertThrows(IllegalArgumentException.class, () -> again.add(new Fog()));
    }

    @Test void aBrokenFileGivesTheDefaults(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("s.properties");
        Files.writeString(f, "fog.density=lots\nfog.on=\\u00zz\n");
        var page = new SettingsPage(SettingsFile.load(f, "test"));
        Fog fog = page.add(new Fog());
        page.restore();
        assertEquals(0.2f, fog.density);
        assertTrue(fog.on);
    }
}
