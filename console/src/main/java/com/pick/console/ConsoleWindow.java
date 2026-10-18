package com.pick.console;

import com.pick.voxel.debug.Theme;
import imgui.ImGui;
import imgui.callback.ImGuiInputTextCallback;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiInputTextFlags;
import imgui.flag.ImGuiKey;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImString;

import java.util.ArrayList;
import java.util.List;

// The console: a panel along the top of the screen with a command line, opened and closed with ` (the key left
// of 1) or F1. It shows what was typed, what agents sent (marked "agent"), and the answers. Up and Down go
// through earlier lines; Tab completes a command's name, or a variable's after get, set and watch; Esc closes it.
//     console.draw(winW, winH)   each frame, between ImGui.newFrame() and ImGui.render()
//     console.isOpen()           while open, the app should free the mouse and keep keys from the game
public final class ConsoleWindow {
    private final Commands commands;
    private final ImString input = new ImString(512);
    private final List<String> history = new ArrayList<>();
    private int historyAt = -1;   // -1: the line being written
    private boolean open, focus, scrollDown, raise;
    private int seen;   // log lines already shown, to scroll down when new ones arrive

    public ConsoleWindow(Commands commands) { this.commands = commands; }

    public boolean isOpen() { return open; }
    public void setOpen(boolean o) {
        if (o && !open) { input.set(""); focus = true; scrollDown = true; raise = true; }
        open = o;
    }

    public void draw(float winW, float winH) {
        boolean toggle = ImGui.isKeyPressed(ImGuiKey.GraveAccent, false) || ImGui.isKeyPressed(ImGuiKey.F1, false);
        if (toggle && (open || !ImGui.getIO().getWantTextInput())) { setOpen(!open); if (!open) return; }
        if (!open) return;

        float h = Math.max(ImGui.getFrameHeight() * 6, winH * 0.4f);
        ImGui.setNextWindowPos(0, 0, ImGuiCond.Always);
        ImGui.setNextWindowSize(winW, h, ImGuiCond.Always);
        ImGui.setNextWindowBgAlpha(0.97f);
        if (raise) { ImGui.setNextWindowFocus(); raise = false; }   // in front of the app's windows
        ImGui.begin("Console", ImGuiWindowFlags.NoTitleBar | ImGuiWindowFlags.NoMove | ImGuiWindowFlags.NoResize
                | ImGuiWindowFlags.NoSavedSettings | ImGuiWindowFlags.NoCollapse);
        ImGui.textDisabled("Console.  Type help for the commands.  F1 or the ` key closes it.");

        float lineH = ImGui.getFrameHeightWithSpacing();
        ImGui.beginChild("##log", 0, -lineH, false, ImGuiWindowFlags.HorizontalScrollbar);
        var log = commands.log();
        for (var l : log) {
            switch (l.kind()) {
                case INPUT -> ImGui.textColored(Theme.ACCENT[0], Theme.ACCENT[1], Theme.ACCENT[2], 1f, "> " + l.text());
                case AGENT_INPUT -> ImGui.textColored(Theme.ACCENT[0], Theme.ACCENT[1], Theme.ACCENT[2], 1f, "agent> " + l.text());
                case ERROR -> ImGui.textColored(Theme.WARN_TEXT[0], Theme.WARN_TEXT[1], Theme.WARN_TEXT[2], 1f, l.text());
                default -> ImGui.textUnformatted(l.text());
            }
        }
        if (log.size() != seen) { seen = log.size(); scrollDown = true; }
        if (scrollDown) { ImGui.setScrollHereY(1f); scrollDown = false; }
        ImGui.endChild();

        ImGui.setNextItemWidth(-1);
        if (focus) { ImGui.setKeyboardFocusHere(); focus = false; }
        int flags = ImGuiInputTextFlags.EnterReturnsTrue | ImGuiInputTextFlags.CallbackHistory | ImGuiInputTextFlags.CallbackCompletion
                | ImGuiInputTextFlags.CallbackCharFilter;
        if (ImGui.inputText("##cmd", input, flags, keys)) {
            String line = input.get().trim();
            if (!line.isEmpty()) {
                commands.submit(line, false);
                if (history.isEmpty() || !history.get(history.size() - 1).equals(line)) history.add(line);
            }
            historyAt = -1;
            input.set("");
            focus = true;   // keep typing
        }
        if (ImGui.isKeyPressed(ImGuiKey.Escape, false)) open = false;
        ImGui.end();
    }

    // Up/Down: earlier lines; Tab: finish the command name, or list the ones that fit
    private final ImGuiInputTextCallback keys = new ImGuiInputTextCallback() {
        @Override public void accept(imgui.ImGuiInputTextCallbackData d) {
            if (d.getEventFlag() == ImGuiInputTextFlags.CallbackCharFilter) {   // ` opens and closes, so it is never typed
                if (d.getEventChar() == '`') d.setEventChar(0);
            } else if (d.getEventFlag() == ImGuiInputTextFlags.CallbackHistory && !history.isEmpty()) {
                if (d.getEventKey() == ImGuiKey.UpArrow) historyAt = historyAt < 0 ? history.size() - 1 : Math.max(0, historyAt - 1);
                else if (d.getEventKey() == ImGuiKey.DownArrow && historyAt >= 0) historyAt = historyAt + 1 >= history.size() ? -1 : historyAt + 1;
                replace(d, historyAt < 0 ? "" : history.get(historyAt));
            } else if (d.getEventFlag() == ImGuiInputTextFlags.CallbackCompletion) {
                String text = d.getBuf();
                int start = text.lastIndexOf(';') + 1;
                String part = text.substring(start).stripLeading();
                List<String> words = Commands.split(part);
                boolean varWord = words.size() >= 1 && part.contains(" ") && List.of("get", "set", "watch", "vars", "unwatch").contains(words.get(0));
                String word = part.endsWith(" ") ? "" : words.isEmpty() ? "" : words.get(words.size() - 1);
                if (part.contains(" ") && !varWord) return;   // the command's name, or a variable's name
                if (varWord && words.get(0).equals("set") && (words.size() > 2 || (words.size() == 2 && part.endsWith(" ")))) return;
                List<String> fits = new ArrayList<>();
                if (varWord) { for (String n : commands.values("").keySet()) if (n.startsWith(word)) fits.add(n); }
                else for (String n : commands.all().keySet()) if (n.startsWith(word.toLowerCase())) fits.add(n);
                if (fits.size() > 1) {   // as far as they all agree
                    String common = fits.get(0);
                    for (String f : fits) while (!f.startsWith(common)) common = common.substring(0, common.length() - 1);
                    if (common.length() > word.length()) { replace(d, text.substring(0, text.length() - word.length()) + common); return; }
                }
                if (fits.size() == 1) replace(d, text.substring(0, text.length() - word.length()) + fits.get(0) + " ");
                else if (fits.size() > 1) commands.print(String.join("  ", fits));
            }
        }
    };

    private static void replace(imgui.ImGuiInputTextCallbackData d, String s) {
        d.deleteChars(0, d.getBufTextLen());
        d.insertChars(0, s);
    }
}
