package com.pick.launcher;

import com.pick.console.Commands;
import com.pick.console.CommandServer;
import com.pick.console.ConsoleWindow;
import com.pick.feedback.FeedbackNotes;
import com.pick.feedback.FeedbackPanel;
import com.pick.settings.SettingsFile;
import com.pick.voxel.debug.Motion;
import com.pick.voxel.debug.Theme;
import com.pick.voxel.debug.Ui;
import com.pick.voxel.debug.UiFonts;
import imgui.ImGui;
import imgui.ImGuiIO;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiWindowFlags;
import imgui.gl3.ImGuiImplGl3;
import imgui.glfw.ImGuiImplGlfw;
import imgui.type.ImString;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;

// The start page: the shelf. The planets you can play, each floating in a small glass case in a dark cabinet;
// Play opens the chosen one in the engine. The workshop (the Builder, the Studio, a new planet) is in the corner
// menu. Each app opens as its own program, built fresh from its repo (docs/launcher.md, docs/design-language.md).
// The agent console (-Dlauncher.console, port 47102) drives this page as well.
//     ./gradlew :launcher:run            (pick-shared)   or tools/launcher
public final class Launcher {
    private static final Path HOME = Path.of(System.getProperty("user.home"));
    private final long window;
    private final ImGuiImplGlfw imguiGlfw = new ImGuiImplGlfw();
    private final ImGuiImplGl3 imguiGl3 = new ImGuiImplGl3();
    private final SettingsFile settings;
    private final ImString projects = new ImString(512);
    private boolean agentConsole;
    private float textScale = 1f;
    private String lastPlanet;
    private final ImString moreShelves = new ImString(1024);
    private List<Planets.Planet> planets = List.of();
    private int sel, base;
    private float pos, spin;
    private final Globes globes = new Globes();
    private final java.util.Set<String> asked = new java.util.HashSet<>();
    private final java.util.concurrent.ConcurrentLinkedQueue<Map.Entry<String, Planets.Colours>> ready = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final java.util.concurrent.ExecutorService loader = java.util.concurrent.Executors.newSingleThreadExecutor(r -> { var t = new Thread(r, "shelf"); t.setDaemon(true); return t; });
    private boolean menuAsked, optionsAsked;
    private double lastScan;
    private final List<App> apps;
    private final Commands commands = new Commands();
    private final ConsoleWindow console = new ConsoleWindow(commands);
    private final FeedbackPanel feedback;
    private String message = "";
    private double messageUntil;
    private String shotPath;
    private CompletableFuture<String> shotDone;

    public static void main(String[] args) { new Launcher().run(); }

    private Launcher() {
        Path cfg = Path.of(System.getProperty("launcher.settings", HOME.resolve(".config/pick-launcher/launcher.properties").toString()));
        settings = SettingsFile.load(cfg, "the launcher's settings (start page)");
        var s = settings.scope("launcher");
        projects.set(s.str("projects", HOME.resolve("IdeaProjects").toString()));
        agentConsole = s.b("agentConsole", false);
        textScale = s.f("textScale", 1f, 1f, 2f);
        lastPlanet = s.str("lastPlanet", "");
        moreShelves.set(s.str("moreShelves", ""));
        Motion.reduce = s.b("reduceMotion", false);
        Path logs = HOME.resolve(".cache/pick-launcher");
        apps = List.of(
                new App(App.Id.ENGINE, "Play", "Walk a planet in first person.", "Play", logs),
                new App(App.Id.BUILDER, "Build planets", "Make and shape planets, then bake them.", "Build planets", logs),
                new App(App.Id.STUDIO, "Make maps", "Maps of your newest baked planet, in the Studio.", "Make maps", logs));

        GLFWErrorCallback.createPrint(System.err).set();
        if (!glfwInit()) throw new IllegalStateException("GLFW did not start");
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        window = glfwCreateWindow(1200, 720, "Worldseed", 0, 0);
        if (window == 0) throw new IllegalStateException("no window");
        glfwSetWindowSizeLimits(window, 760, 520, GLFW_DONT_CARE, GLFW_DONT_CARE);
        glfwMakeContextCurrent(window);
        glfwSwapInterval(1);
        GL.createCapabilities();
        ImGui.createContext();
        ImGuiIO io = ImGui.getIO();
        io.setIniFilename(null);
        UiFonts.load(io);
        imguiGlfw.init(window, true);
        imguiGl3.init("#version 330");
        Theme.apply();
        feedback = new FeedbackPanel(new FeedbackNotes(HOME.resolve(".cache/pick-launcher/feedback"), "launcher"), this::snapshot);
        addCommands();
        rescan();
        CommandServer.startIfAsked(commands, "launcher.console", 47102);
        if (System.getProperty("launcher.run") != null) commands.submit(System.getProperty("launcher.run"), true);
    }

    private void run() {
        double last = glfwGetTime();
        while (!glfwWindowShouldClose(window)) {
            glfwPollEvents();
            double now = glfwGetTime(), dt = now - last;
            last = now;
            int[] w = {0}, h = {0}, fw = {0}, fh = {0};
            glfwGetWindowSize(window, w, h);
            glfwGetFramebufferSize(window, fw, fh);
            glViewport(0, 0, fw[0], fh[0]);
            if (now - lastScan > 4) { lastScan = now; rescan(); }   // a planet baked meanwhile appears
            drawGlobes(w[0], h[0], fw[0], fh[0], (float) Math.min(dt, 0.1));
            glClearColor(0.035f, 0.027f, 0.022f, 1f);   // the cabinet's dark
            glClear(GL_COLOR_BUFFER_BIT);
            UiFonts.scale(UiFonts.contentScale(window), textScale);
            imguiGlfw.newFrame();
            imguiGl3.newFrame();
            ImGui.newFrame();
            drawPage(w[0], h[0], (float) Math.min(dt, 0.1));
            feedback.draw(w[0], h[0]);
            if (shotPath == null) console.draw(w[0], h[0]);
            ImGui.render();
            imguiGl3.renderDrawData(ImGui.getDrawData());
            java.nio.file.Path feedbackShot = feedback.takeShotRequest();   // a feedback note's picture of this frame
            if (feedbackShot != null) try { savePng(fw[0], fh[0], feedbackShot); } catch (Exception e) { System.err.println("[feedback] picture not saved: " + e); }
            if (shotPath != null) {
                try { savePng(fw[0], fh[0], Path.of(shotPath)); shotDone.complete(Path.of(shotPath).toAbsolutePath().toString()); }
                catch (Exception e) { shotDone.completeExceptionally(e); }
                shotPath = null;
            }
            commands.tick(dt);
            glfwSwapBuffers(window);
        }
        save();
        imguiGl3.shutdown();
        imguiGlfw.shutdown();
        ImGui.destroyContext();
        glfwDestroyWindow(window);
        glfwTerminate();
        System.exit(0);   // the apps it opened keep running: they are their own programs
    }

    // ---- the page: the shelf ----

    private void drawPage(float winW, float winH, float dt) {
        float pad = UiFonts.px(28);
        ImGui.setNextWindowPos(0, 0, ImGuiCond.Always);
        ImGui.setNextWindowSize(winW, winH, ImGuiCond.Always);
        ImGui.setNextWindowBgAlpha(0f);
        ImGui.pushStyleVar(imgui.flag.ImGuiStyleVar.WindowBorderSize, 0f);
        ImGui.begin("##start", ImGuiWindowFlags.NoDecoration | ImGuiWindowFlags.NoMove | ImGuiWindowFlags.NoSavedSettings
                | ImGuiWindowFlags.NoBringToFrontOnFocus | ImGuiWindowFlags.NoScrollWithMouse);
        ImGui.popStyleVar();
        var dl = ImGui.getWindowDrawList();
        int count = planets.size() + 1;   // and the empty case at the end: New planet
        sel = Math.clamp(sel, 0, count - 1);
        pos = Motion.approach(pos, sel, 9f, dt);

        // the shelf: cases in a row, the chosen one nearest, the rest falling back into the dark
        float cs = caseSize(winW, winH), gap = cs * 0.16f;
        float cx = winW / 2, cy = winH * 0.43f;
        float plankY = cy + cs / 2 + UiFonts.px(6);
        dl.addRectFilledMultiColor(0, plankY, winW, plankY + UiFonts.px(16), Ui.pack(0.20f, 0.15f, 0.11f, 1f), Ui.pack(0.20f, 0.15f, 0.11f, 1f), Ui.pack(0.07f, 0.05f, 0.04f, 1f), Ui.pack(0.07f, 0.05f, 0.04f, 1f));
        dl.addLine(0, plankY, winW, plankY, Ui.pack(0.36f, 0.28f, 0.20f, 1f), 1f);
        int clicked = -1;
        for (int i = 0; i < count; i++) {
            float d = i - pos;
            if (Math.abs(d) > SIDE + 0.5f) continue;
            float k = 1f - Math.min(Math.abs(d), 2f) * 0.13f, w = cs * k;
            float x = cx + d * (cs + gap) - w / 2, y = plankY - w;
            if (x > winW || x + w < 0) continue;
            boolean chosen = i == sel;
            drawCase(dl, i, x, y, w, chosen);
            ImGui.setCursorScreenPos(x, y);
            ImGui.invisibleButton("##case" + i, w, w);
            if (ImGui.isItemClicked()) clicked = i;
            if (chosen && ImGui.isItemHovered() && ImGui.isMouseDoubleClicked(0)) playChosen();
            if (!chosen && ImGui.isItemHovered()) ImGui.setTooltip(i < planets.size() ? planets.get(i).name() : "New planet");
        }
        if (clicked >= 0) sel = clicked;

        // the dark at both ends of the shelf (and a little above and below)
        float fade = Math.min(winW * 0.24f, cs * 1.6f);
        int black = Ui.pack(0f, 0f, 0f, 1f), clear = Ui.pack(0f, 0f, 0f, 0f);
        dl.addRectFilledMultiColor(0, 0, fade, winH, black, clear, clear, black);
        dl.addRectFilledMultiColor(winW - fade, 0, winW, winH, clear, black, black, clear);

        // the chosen planet: its name, a line about it, and Play
        float ty = plankY + UiFonts.px(34);
        boolean isNew = sel >= planets.size();
        String name = isNew ? "New planet" : planets.get(sel).name();
        ImGui.setWindowFontScale(1.5f);
        centred(name, ty, Theme.TEXT_RGB);
        ImGui.setWindowFontScale(1f);
        ty += ImGui.getTextLineHeight() * 1.5f + UiFonts.px(4);
        String about = isNew ? "Shape a world of your own in the Builder. It takes a while: a project, not a quick game."
                : about(planets.get(sel));
        centred(about, ty, Theme.MUTED_RGB);
        ty += ImGui.getTextLineHeight() + UiFonts.px(18);

        App engine = app(App.Id.ENGINE), builder = app(App.Id.BUILDER);
        App doing = isNew ? builder : engine;
        boolean running = doing.isRunning();
        float bw = UiFonts.px(240), bh = UiFonts.px(44);
        ImGui.setCursorScreenPos(cx - bw / 2, ty);
        if (!running) Theme.pushPrimary();
        ImGui.setWindowFontScale(1.15f);
        String label = running ? (isNew ? "Close the Builder" : "Stop playing") : isNew ? "Make a new planet" : "Play";
        if (ImGui.button(label + "##main", bw, bh)) { if (running) doing.stop(); else if (isNew) newPlanet(); else playChosen(); }
        ImGui.setWindowFontScale(1f);
        if (!running) Theme.popPrimary();
        if (!running) Ui.tip(isNew ? "opens the Builder with a new planet" : "walk this planet in first person (Enter, or double-click its case)");
        ty += bh + UiFonts.px(8);
        String st = status(doing);
        if (!st.isEmpty()) {
            float tw = Math.min(ImGui.calcTextSize(st).x, winW - 2 * pad);
            ImGui.setCursorScreenPos(cx - tw / 2, ty);
            if (doing.state() == App.State.FAILED) ImGui.textColored(Theme.WARN_TEXT[0], Theme.WARN_TEXT[1], Theme.WARN_TEXT[2], 1f, ellipsize(st, tw).replace("%", "%%"));
            else ImGui.textDisabled(ellipsize(st, tw).replace("%", "%%"));
            if (ImGui.isItemHovered() && Files.exists(doing.log())) ImGui.setTooltip(("its log: " + doing.log() + "\nclick to open it").replace("%", "%%"));
            if (ImGui.isItemClicked() && Files.exists(doing.log())) openFile(doing.log());
        }
        if (planets.isEmpty()) centred("No planets yet. Bake one in the Builder and it appears here.", ty + UiFonts.px(30), Theme.MUTED_RGB);

        // keys: left and right along the shelf, Enter plays
        if (!ImGui.getIO().getWantTextInput() && !ImGui.isPopupOpen("", imgui.flag.ImGuiPopupFlags.AnyPopupId)) {
            if (ImGui.isKeyPressed(imgui.flag.ImGuiKey.LeftArrow)) sel = Math.max(0, sel - 1);
            if (ImGui.isKeyPressed(imgui.flag.ImGuiKey.RightArrow)) sel = Math.min(count - 1, sel + 1);
            if (ImGui.isKeyPressed(imgui.flag.ImGuiKey.Enter) && !engine.isRunning()) { if (isNew) newPlanet(); else playChosen(); }
            float wheel = ImGui.getIO().getMouseWheel() + ImGui.getIO().getMouseWheelH();
            if (wheel != 0 && ImGui.isWindowHovered()) sel = Math.clamp(sel + (wheel < 0 ? 1 : -1), 0, count - 1);
        }

        // the name, quietly, and the corner menu: the workshop is there, not in the way
        ImGui.setCursorPos(pad, pad);
        ImGui.textDisabled("WORLDSEED");
        float mw = ImGui.calcTextSize("Menu").x + UiFonts.px(44);
        ImGui.setCursorPos(winW - pad - mw, pad - UiFonts.px(6));
        if (ImGui.button("=  Menu##menu", mw, 0)) ImGui.openPopup("menu");
        menuAt = new float[]{winW - pad, pad + ImGui.getFrameHeight()};
        Ui.tip("Build planets, Make maps, Options");
        if (menuAsked) { ImGui.openPopup("menu"); menuAsked = false; }
        menu();
        if (optionsAsked) { ImGui.openPopup("Options"); optionsAsked = false; }
        options();

        if (!message.isEmpty() && glfwGetTime() < messageUntil) {
            float tw = Math.min(ImGui.calcTextSize(message).x, winW - 2 * pad);
            ImGui.setCursorScreenPos(cx - tw / 2, winH - pad - ImGui.getTextLineHeight());
            ImGui.textColored(Theme.WARN_TEXT[0], Theme.WARN_TEXT[1], Theme.WARN_TEXT[2], 1f, ellipsize(message, tw).replace("%", "%%"));
        }
        ImGui.end();
    }

    private static final int SIDE = 3;   // cases drawn on each side of the chosen one

    private float caseSize(float winW, float winH) { return Math.clamp(Math.min(winH * 0.42f, winW * 0.25f), 120f, 380f); }

    // A glass case: its dark inside, the globe and its light, an edge, a glint along the top, a plinth
    private void drawCase(imgui.ImDrawList dl, int i, float x, float y, float w, boolean chosen) {
        float r = Math.max(6, w * 0.06f);
        dl.addRectFilled(x, y, x + w, y + w, Ui.pack(0.055f, 0.04f, 0.032f, 0.96f), r);
        int slot = i - base;
        if (slot >= 0 && slot < SLOTS) {
            float[] uv = globes.uv(slot);
            dl.addImage(globes.texture(), x, y, x + w, y + w, uv[0], uv[1], uv[2], uv[3]);
        }
        if (i >= planets.size()) {   // the empty case: a plus where a planet would float
            float c = w * 0.12f, mx = x + w / 2, my = y + w / 2;
            int dim = Ui.pack(0.659f, 0.592f, 0.498f, chosen ? 0.9f : 0.5f);
            dl.addLine(mx - c, my, mx + c, my, dim, Math.max(1.5f, w / 90));
            dl.addLine(mx, my - c, mx, my + c, dim, Math.max(1.5f, w / 90));
        }
        float[] e = chosen ? Theme.MUTED_RGB : Theme.EDGE_RGB;
        dl.addRect(x, y, x + w, y + w, Ui.pack(e[0], e[1], e[2], chosen ? 1f : 0.8f), r, 0, chosen ? 1.6f : 1f);
        dl.addLine(x + r, y + 2, x + w - r, y + 2, Ui.pack(1f, 1f, 1f, chosen ? 0.16f : 0.08f), 1f);   // glint
        float ph = Math.max(4, w * 0.035f);
        dl.addRectFilled(x - w * 0.03f, y + w, x + w * 1.03f, y + w + ph, Ui.pack(0.13f, 0.10f, 0.075f, 1f), 2f);   // plinth
        if (!chosen && i < planets.size()) {
            String n = ellipsize(planets.get(i).name(), w);
            float tw = ImGui.calcTextSize(n).x;
            dl.addText(x + (w - tw) / 2, y + w + ph + UiFonts.px(6), Ui.pack(0.659f, 0.592f, 0.498f, 0.8f), n);
        }
    }

    // Draw the globes in the cases on screen into their pictures, before ImGui draws the page
    private void drawGlobes(float winW, float winH, int fbW, int fbH, float dt) {
        while (true) {   // surfaces read since the last frame
            var done = ready.poll();
            if (done == null) break;
            globes.upload(done.getKey(), done.getValue());
        }
        if (Motion.ambient()) spin += dt * 0.12f;
        float scale = winW > 0 ? fbW / winW : 1;
        int size = Math.round(caseSize(winW, winH) * scale);
        base = Math.round(pos) - SIDE;
        globes.begin(SLOTS, Math.min(size, 512));
        for (int s = 0; s < SLOTS; s++) {
            int i = base + s;
            if (i < 0 || i > planets.size()) continue;
            if (i == planets.size()) { globes.draw(s, null, 0, i == sel ? 0.35f : 0.15f); continue; }
            var p = planets.get(i);
            if (!globes.has(p.key())) request(p);
            float light = 1f - Math.min(Math.abs(i - pos), 2f) * 0.3f;
            globes.draw(s, p.key(), spin + i * 1.3f, light);
        }
        globes.end(fbW, fbH);
    }

    private static final int SLOTS = 2 * SIDE + 2;

    private void request(Planets.Planet p) {
        if (!asked.add(p.key())) return;
        loader.submit(() -> {
            try { ready.add(Map.entry(p.key(), Planets.colours(p, 128))); }
            catch (Exception e) { System.err.println("[shelf] " + p.name() + ": " + e.getMessage()); }
        });
    }

    private void rescan() {
        boolean onNew = !planets.isEmpty() && sel >= planets.size();   // the empty case stays chosen
        String keep = sel < planets.size() ? planets.get(sel).key() : lastPlanet;
        planets = Planets.find(shelves());
        sel = onNew ? planets.size() : 0;
        for (int i = 0; i < planets.size() && !onNew; i++) if (planets.get(i).key().equals(keep)) sel = i;
        if (Math.abs(pos - sel) > 3) pos = sel;
    }

    // Where planets come from: the Builder's worlds (run from its repo or installed) and a folder of packages
    private List<Path> shelves() {
        Path pr = Path.of(projects.get().trim());
        var l = new java.util.ArrayList<>(List.of(pr.resolve("world-builder/worlds"), HOME.resolve("WorldSeed/worlds"), pr.resolve("world-packages")));
        for (String extra : moreShelves.get().split(";")) if (!extra.isBlank()) l.add(Path.of(extra.trim()));
        return l;
    }

    private static String about(Planets.Planet p) {
        double km = 2 * p.radiusM() / 1000;
        String size = km >= 10 ? String.format("%.0f km across", km) : String.format("%.1f km across", km);
        long ago = (System.currentTimeMillis() - p.madeAt()) / 1000;
        String when = ago < 3600 ? "made just now" : ago < 86400 ? "made " + ago / 3600 + " h ago" : ago < 86400 * 60 ? "made " + ago / 86400 + " days ago"
                : "made " + java.time.LocalDate.ofInstant(java.time.Instant.ofEpochMilli(p.madeAt()), java.time.ZoneId.systemDefault());
        return size + "  ·  " + when;
    }

    private String status(App a) {
        return switch (a.state()) {
            case RUNNING -> (a.lastLine().startsWith("starting") ? "starting (it builds first), " : "open, ") + clock(a.runningSeconds());
            case FAILED -> "it stopped: " + a.reason();
            case CLOSED -> "";
        };
    }

    private static void centred(String s, float y, float[] rgb) {
        float w = ImGui.getWindowWidth(), tw = Math.min(ImGui.calcTextSize(s).x, w - 2 * UiFonts.px(28));
        ImGui.setCursorScreenPos(ImGui.getWindowPosX() + (w - tw) / 2, y);
        ImGui.pushTextWrapPos(ImGui.getCursorPosX() + tw + 1);
        ImGui.textColored(rgb[0], rgb[1], rgb[2], 1f, s.replace("%", "%%"));
        ImGui.popTextWrapPos();
    }

    // The corner menu: the workshop
    private float[] menuAt = {0, 0};

    private void menu() {
        ImGui.setNextWindowPos(menuAt[0], menuAt[1], ImGuiCond.Always, 1f, 0f);   // under its button, right edges lined up
        if (!ImGui.beginPopup("menu")) return;
        ImGui.textDisabled("The workshop");
        tool(app(App.Id.BUILDER), "Build planets", "make and shape planets, then bake them");
        tool(app(App.Id.STUDIO), "Make maps", "maps of your newest baked planet, in the Studio");
        if (ImGui.menuItem("New planet")) newPlanet();
        ImGui.separator();
        if (ImGui.menuItem("Options...")) optionsAsked = true;
        if (ImGui.menuItem("Look for planets again")) rescan();
        ImGui.endPopup();
    }

    private void tool(App a, String title, String tip) {
        boolean running = a.isRunning();
        if (ImGui.menuItem(running ? "Close: " + title : title, running ? clock(a.runningSeconds()) : "")) { if (running) a.stop(); else open(a, null); }
        Ui.tip(running ? "close it (it asks about unsaved work where it can)" : tip);
        String st = a.state() == App.State.FAILED ? status(a) : "";
        if (!st.isEmpty()) { ImGui.textColored(Theme.WARN_TEXT[0], Theme.WARN_TEXT[1], Theme.WARN_TEXT[2], 1f, ellipsize(st, UiFonts.px(320)).replace("%", "%%")); if (ImGui.isItemClicked()) openFile(a.log()); }
    }

    private void options() {
        float vw = ImGui.getMainViewport().getSizeX(), vh = ImGui.getMainViewport().getSizeY();
        ImGui.setNextWindowSize(Math.min(UiFonts.px(560), vw - 32), 0, ImGuiCond.Always);
        ImGui.setNextWindowPos(vw / 2, vh / 2, ImGuiCond.Always, 0.5f, 0.5f);
        if (!ImGui.beginPopupModal("Options", ImGuiWindowFlags.NoResize | ImGuiWindowFlags.AlwaysAutoResize)) return;
        ImGui.text("Projects folder");
        ImGui.setNextItemWidth(-1);
        if (ImGui.inputText("##projects", projects)) { save(); rescan(); }
        Ui.tip("the folder that holds voxel, world-builder and pick-shared (each app is built from there)");
        ImGui.text("Planets also from");
        ImGui.setNextItemWidth(-1);
        if (ImGui.inputTextWithHint("##shelves", "more folders, separated by ;", moreShelves)) { save(); rescan(); }
        Ui.tip("folders of baked planets besides the Builder's own (a Builder's worlds folder, or World Packages)");
        if (ImGui.checkbox("Agent console", agentConsole)) { agentConsole = !agentConsole; save(); }
        Ui.tip("open the apps with their console port, so an agent can drive them (engine 47100, Builder 47101; tools/console.py)");
        if (ImGui.checkbox("Reduce motion", Motion.reduce)) { Motion.reduce = !Motion.reduce; save(); }
        Ui.tip("the shelf jumps instead of sliding, and the planets stop turning");
        ImGui.text("Text size");
        ImGui.sameLine();
        ImGui.setNextItemWidth(UiFonts.px(170));
        int ti = UiFonts.textScaleIndex(textScale);
        if (ImGui.beginCombo("##text", UiFonts.TEXT_SCALE_NAMES[ti])) {
            for (int i = 0; i < UiFonts.TEXT_SCALES.length; i++)
                if (ImGui.selectable(UiFonts.TEXT_SCALE_NAMES[i], i == ti)) { textScale = UiFonts.TEXT_SCALES[i]; save(); }
            ImGui.endCombo();
        }
        Ui.tip("the size of the lettering here (each app has its own in its Settings)");
        ImGui.spacing();
        if (ImGui.button("Done", UiFonts.px(120), 0) || ImGui.isKeyPressed(imgui.flag.ImGuiKey.Escape)) ImGui.closeCurrentPopup();
        ImGui.endPopup();
    }

    private static String ellipsize(String s, float w) {
        if (ImGui.calcTextSize(s).x <= w) return s;
        while (s.length() > 4 && ImGui.calcTextSize(s + "...").x > w) s = s.substring(0, s.length() - 1);
        return s + "...";
    }

    private static String clock(long s) { return s < 60 ? s + " s" : String.format("%d:%02d", s / 60, s % 60); }

    // ---- doing things ----

    private void open(App a, List<String> extra) {
        try {
            a.start(Path.of(projects.get().trim()), agentConsole, custom(a), extra);
        } catch (Exception e) {
            say("couldn't open it: " + e.getMessage());
        }
    }

    private void playChosen() {
        if (sel >= planets.size()) { newPlanet(); return; }
        var p = planets.get(sel);
        App engine = app(App.Id.ENGINE);
        if (engine.isRunning()) { say("a planet is already open: stop it first"); return; }
        lastPlanet = p.key();
        save();
        open(engine, List.of("-Dvoxel.world=" + p.pkg().toAbsolutePath()));
    }

    private void newPlanet() { open(app(App.Id.BUILDER), List.of("-Dbuilder.run=new")); }

    private App app(App.Id id) { for (App a : apps) if (a.id == id) return a; throw new IllegalStateException(); }

    private String custom(App a) { return settings.scope(a.id.name().toLowerCase()).str("command", ""); }

    private void say(String s) { message = s; messageUntil = glfwGetTime() + Math.max(4, s.length() / 20.0); }

    private void save() {
        var s = settings.scope("launcher");
        s.put("projects", projects.get().trim());
        s.put("agentConsole", agentConsole);
        s.put("textScale", textScale);
        s.put("lastPlanet", lastPlanet == null ? "" : lastPlanet);
        s.put("moreShelves", moreShelves.get().trim());
        s.put("reduceMotion", Motion.reduce);
        settings.save();
    }

    private static void openFile(Path p) {
        String os = System.getProperty("os.name", "").toLowerCase();
        String[] cmd = os.contains("win") ? new String[]{"explorer", p.toString()} : os.contains("mac") ? new String[]{"open", p.toString()} : new String[]{"xdg-open", p.toString()};
        try { new ProcessBuilder(cmd).start(); } catch (Exception ignored) { }
    }

    private Map<String, Object> snapshot() {
        var m = new LinkedHashMap<String, Object>();
        for (App a : apps) m.put(a.id.name().toLowerCase(), a.state().name().toLowerCase());
        m.put("projects", projects.get());
        m.put("planet", sel < planets.size() ? planets.get(sel).name() : "new planet");
        m.put("planets", planets.size());
        m.put("agentConsole", agentConsole);
        return m;
    }

    private void addCommands() {
        var c = commands;
        c.screenshots(f -> {
            if (shotPath != null) throw new IllegalStateException("a shot is already waiting");
            Path p = Path.of(f);
            if (!p.isAbsolute()) p = HOME.resolve(".cache/pick-launcher/shots").resolve(p);   // not into whatever folder it was started from
            try { Files.createDirectories(p.toAbsolutePath().getParent()); } catch (java.io.IOException e) { throw new IllegalStateException(e); }
            shotPath = p.toString();
            shotDone = new CompletableFuture<>();
            return shotDone;
        });
        c.add("open", "open <engine|builder|studio>", "open an app (engine: the chosen planet)", a -> {
            App app = app(a.str(0));
            if (app.id == App.Id.ENGINE) playChosen(); else open(app, null);
            if (!app.isRunning()) throw new IllegalStateException(message);
            return app.title + ": starting";
        });
        c.add("stop", "stop <engine|builder|studio>", "close an app the launcher opened", a -> { app(a.str(0)).stop(); return null; });
        c.add("planets", "planets", "the planets on the shelf, the chosen one marked", a -> {
            var b = new StringBuilder();
            for (int i = 0; i < planets.size(); i++) b.append(i == sel ? "> " : "  ").append(i + 1).append("  ").append(planets.get(i).name()).append("  (").append(planets.get(i).pkg()).append(")\n");
            return planets.isEmpty() ? "no planets yet (" + shelves() + ")" : b.toString().stripTrailing();
        });
        c.add("pick", "pick <number|name|new>", "choose a case on the shelf", a -> {
            String w = a.rest(0);
            if (w.equalsIgnoreCase("new")) { sel = planets.size(); return null; }
            try { int n = Integer.parseInt(w); if (n >= 1 && n <= planets.size()) { sel = n - 1; return null; } } catch (NumberFormatException ignored) { }
            for (int i = 0; i < planets.size(); i++) if (planets.get(i).name().equalsIgnoreCase(w)) { sel = i; return null; }
            throw new IllegalArgumentException("no planet \"" + w + "\" (planets lists them)");
        });
        c.add("play", "play", "play the chosen planet, as Play does", a -> { playChosen(); return message.isEmpty() || glfwGetTime() >= messageUntil ? "starting" : message; });
        c.add("menu", "menu", "open the corner menu", a -> { menuAsked = true; return null; });
        c.add("options", "options", "open Options", a -> { optionsAsked = true; return null; });
        c.add("apps", "apps", "what each app is doing", a -> {
            var b = new StringBuilder();
            for (App app : apps) b.append(String.format("%-8s %-8s %s%n", app.id.name().toLowerCase(), app.state().name().toLowerCase(), app.lastLine()));
            return b.toString().stripTrailing();
        });
        c.add("text", "text <100|125|150|200>", "the Text size, in percent", a -> { textScale = (float) Math.clamp(a.num(0) / 100, 1, 2); save(); return null; });
        c.add("window", "window <width> <height>", "resize the window", a -> { glfwSetWindowSize(window, (int) a.num(0), (int) a.num(1)); return null; });
        c.add("console", "console [on|off]", "open or close this console (` or F1)", a -> { console.setOpen(a.on(0, !console.isOpen())); return null; });
        c.add("quit", "quit", "close the launcher (the apps it opened stay open)", a -> { glfwSetWindowShouldClose(window, true); return null; });
        c.var("projects", "the folder that holds the repos", projects::get, v -> { projects.set(v); save(); rescan(); });
        c.var("moreShelves", "more folders of planets, separated by ;", moreShelves::get, v -> { moreShelves.set(v); save(); rescan(); });
        c.value("planet", "the chosen case", () -> sel < planets.size() ? planets.get(sel).name() : "new planet");
        c.flag("agentConsole", "open the apps with their console port", () -> agentConsole, v -> { agentConsole = v; save(); });
        for (App app : apps) c.value("app." + app.id.name().toLowerCase(), "what it is doing", () -> { String l = app.lastLine(); return app.state().name().toLowerCase() + (l.isBlank() ? "" : ": " + l); });
    }

    private App app(String name) {
        for (App a : apps) if (a.id.name().equalsIgnoreCase(name)) return a;
        throw new IllegalArgumentException("engine, builder or studio");
    }

    private static void savePng(int w, int h, Path file) throws Exception {
        ByteBuffer px = org.lwjgl.BufferUtils.createByteBuffer(w * h * 4);
        glReadPixels(0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, px);
        var img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                int i = ((h - 1 - y) * w + x) * 4;
                img.setRGB(x, y, ((px.get(i) & 0xFF) << 16) | ((px.get(i + 1) & 0xFF) << 8) | (px.get(i + 2) & 0xFF));
            }
        if (file.toAbsolutePath().getParent() != null) Files.createDirectories(file.toAbsolutePath().getParent());
        javax.imageio.ImageIO.write(img, "png", file.toFile());
    }
}
