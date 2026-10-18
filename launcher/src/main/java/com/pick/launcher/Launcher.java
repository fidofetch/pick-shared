package com.pick.launcher;

import com.pick.console.Commands;
import com.pick.console.CommandServer;
import com.pick.console.ConsoleWindow;
import com.pick.feedback.FeedbackNotes;
import com.pick.feedback.FeedbackPanel;
import com.pick.settings.SettingsFile;
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

// The start page: one window that opens the engine (walk the planet), the Builder (make planets) or the Studio
// (maps of a baked planet). Each opens as its own program, built fresh from its repo; the cards say what each is
// doing and stop it when asked. The agent console (-Dlauncher.console, port 47102) drives this page as well.
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
    private String lastOpened;
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
        lastOpened = s.str("lastOpened", "BUILDER");
        Path logs = HOME.resolve(".cache/pick-launcher");
        apps = List.of(
                new App(App.Id.ENGINE, "Play", "Walk the planet: the voxel engine, in first person.", "Open the engine", logs),
                new App(App.Id.BUILDER, "Build planets", "Make and shape planets on the shelf, then bake them.", "Open the Builder", logs),
                new App(App.Id.STUDIO, "Make maps", "Maps of your newest baked planet, in the Studio.", "Open the Studio", logs));

        GLFWErrorCallback.createPrint(System.err).set();
        if (!glfwInit()) throw new IllegalStateException("GLFW did not start");
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        window = glfwCreateWindow(1000, 640, "Worldseed", 0, 0);
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
            glClearColor(0.07f, 0.055f, 0.045f, 1f);
            glClear(GL_COLOR_BUFFER_BIT);
            UiFonts.scale(UiFonts.contentScale(window), textScale);
            imguiGlfw.newFrame();
            imguiGl3.newFrame();
            ImGui.newFrame();
            drawPage(w[0], h[0]);
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

    // ---- the page ----

    private void drawPage(float winW, float winH) {
        float pad = UiFonts.px(28);
        ImGui.setNextWindowPos(0, 0, ImGuiCond.Always);
        ImGui.setNextWindowSize(winW, winH, ImGuiCond.Always);
        ImGui.setNextWindowBgAlpha(0f);
        ImGui.begin("##start", ImGuiWindowFlags.NoDecoration | ImGuiWindowFlags.NoMove | ImGuiWindowFlags.NoSavedSettings
                | ImGuiWindowFlags.NoBringToFrontOnFocus);
        ImGui.setCursorPos(pad, pad);
        ImGui.textColored(Theme.ACCENT[0], Theme.ACCENT[1], Theme.ACCENT[2], 1f, "WORLDSEED");
        ImGui.setCursorPosX(pad);
        ImGui.setWindowFontScale(1.6f);
        ImGui.text("What would you like to do?");
        ImGui.setWindowFontScale(1f);
        ImGui.spacing(); ImGui.spacing();

        // the three cards, side by side (stacked when the window is narrow)
        float gap = UiFonts.px(16);
        float avail = winW - 2 * pad;
        boolean row = avail >= 3 * UiFonts.px(240) + 2 * gap;
        float cw = row ? (avail - 2 * gap) / 3 : avail;
        float ch = UiFonts.px(250);   // side by side: one height, so the buttons line up; stacked: as tall as each needs
        float y = ImGui.getCursorPosY();
        for (int i = 0; i < apps.size(); i++) {
            ImGui.setCursorPos(pad + (row ? i * (cw + gap) : 0), y);
            card(apps.get(i), cw, row ? ch : 0, row);
            if (!row) y = ImGui.getCursorPosY() + gap;
        }
        float yEnd = (row ? y + ch : y - gap) + UiFonts.px(22);

        // the options under the cards
        ImGui.setCursorPos(pad, yEnd);
        ImGui.separator();
        ImGui.setCursorPosX(pad);
        ImGui.text("Projects folder");
        ImGui.sameLine();
        ImGui.setNextItemWidth(Math.min(UiFonts.px(420), avail - UiFonts.px(140)));
        if (ImGui.inputText("##projects", projects)) save();
        Ui.tip("the folder that holds voxel, world-builder and pick-shared (each app is built from there)");
        ImGui.setCursorPosX(pad);
        if (ImGui.checkbox("Agent console", agentConsole)) { agentConsole = !agentConsole; save(); }
        Ui.tip("open the apps with their console port, so an agent can drive them (engine 47100, Builder 47101; tools/console.py)");
        ImGui.sameLine(0, UiFonts.px(28));
        ImGui.text("Text size");
        ImGui.sameLine();
        ImGui.setNextItemWidth(UiFonts.px(170));
        int ti = UiFonts.textScaleIndex(textScale);
        if (ImGui.beginCombo("##text", UiFonts.TEXT_SCALE_NAMES[ti])) {
            for (int i = 0; i < UiFonts.TEXT_SCALES.length; i++)
                if (ImGui.selectable(UiFonts.TEXT_SCALE_NAMES[i], i == ti)) { textScale = UiFonts.TEXT_SCALES[i]; save(); }
            ImGui.endCombo();
        }
        Ui.tip("the size of the lettering on this page (each app has its own in its Settings)");
        if (!message.isEmpty() && glfwGetTime() < messageUntil) {
            ImGui.setCursorPosX(pad);
            ImGui.textColored(Theme.WARN_TEXT[0], Theme.WARN_TEXT[1], Theme.WARN_TEXT[2], 1f, message.replace("%", "%%"));
        }
        ImGui.end();
    }

    private void card(App a, float w, float h, boolean tall) {
        int childFlags = imgui.flag.ImGuiChildFlags.Borders | (h == 0 ? imgui.flag.ImGuiChildFlags.AutoResizeY : 0);
        ImGui.beginChild("##card" + a.id, w, h, childFlags, ImGuiWindowFlags.NoScrollbar);
        var dl = ImGui.getWindowDrawList();
        float x = ImGui.getCursorScreenPosX(), y = ImGui.getCursorScreenPosY();
        float icon = UiFonts.px(tall ? 56 : 40);
        drawIcon(dl, a.id, x + icon / 2, y + icon / 2, icon / 2);
        if (tall) ImGui.dummy(icon, icon + UiFonts.px(6));
        else { ImGui.dummy(icon, icon); ImGui.sameLine(0, UiFonts.px(14)); ImGui.beginGroup(); }
        ImGui.setWindowFontScale(1.25f);
        ImGui.text(a.title);
        ImGui.setWindowFontScale(1f);
        ImGui.pushTextWrapPos(0);
        ImGui.textColored(0.925f * 0.9f, 0.882f * 0.9f, 0.804f * 0.9f, 1f, a.blurb);
        ImGui.popTextWrapPos();
        if (!tall) ImGui.endGroup();

        // its button: the app opened last is the one accent (the likely next step), the others outlined
        float bw = ImGui.getContentRegionAvailX(), bh = UiFonts.px(34);
        if (tall) ImGui.setCursorPosY(h - bh - UiFonts.px(44));
        boolean running = a.isRunning();
        boolean main = a.id.name().equals(lastOpened) && !running;
        if (main) Theme.pushPrimary(); else Theme.pushOutlined();
        String label = running ? "Stop##" + a.id : a.button + "##" + a.id;
        if (ImGui.button(label, bw, bh)) { if (running) a.stop(); else open(a); }
        if (main) Theme.popPrimary(); else Theme.popOutlined();
        Ui.tip(running ? "close it (its unsaved work: the app asks first where it can)" : String.join(" ", App.command(a.id, Path.of(projects.get()), agentConsole, custom(a))));
        // what it is doing
        String status = switch (a.state()) {
            case RUNNING -> "running " + clock(a.runningSeconds()) + ":  " + a.lastLine();
            case FAILED -> "stopped with an error (" + a.exitCode() + "): " + a.lastLine();
            case CLOSED -> Files.exists(a.log()) && a.exitCode() == 0 && a.lastLine().length() > 0 && !a.lastLine().startsWith("starting") ? "closed" : "not open";
        };
        if (a.state() == App.State.FAILED) ImGui.textColored(Theme.WARN_TEXT[0], Theme.WARN_TEXT[1], Theme.WARN_TEXT[2], 1f, ellipsize(status, bw));
        else ImGui.textDisabled(ellipsize(status, bw).replace("%", "%%"));
        if (ImGui.isItemHovered() && Files.exists(a.log())) ImGui.setTooltip(("its log: " + a.log() + "\nclick to open it").replace("%", "%%"));
        if (ImGui.isItemClicked() && Files.exists(a.log())) openFile(a.log());
        ImGui.endChild();
    }

    private static String ellipsize(String s, float w) {
        if (ImGui.calcTextSize(s).x <= w) return s;
        while (s.length() > 4 && ImGui.calcTextSize(s + "...").x > w) s = s.substring(0, s.length() - 1);
        return s + "...";
    }

    private static String clock(long s) { return s < 60 ? s + " s" : String.format("%d:%02d", s / 60, s % 60); }

    // Simple marks drawn with lines: a walker on a planet's curve, a globe with its grid, a folded map
    private static void drawIcon(imgui.ImDrawList dl, App.Id id, float cx, float cy, float r) {
        int acc = Ui.pack(Theme.ACCENT), dim = Ui.pack(0.478f, 0.384f, 0.286f, 1f);
        float t = Math.max(1.5f, r / 14);
        switch (id) {
            case ENGINE -> {
                dl.pathArcTo(cx, cy + r * 1.68f, r * 1.3f, (float) (Math.PI * 1.25), (float) (Math.PI * 1.75), 24);
                dl.pathStroke(dim, 0, t);
                dl.addCircleFilled(cx, cy - r * 0.45f, r * 0.16f, acc);
                dl.addLine(cx, cy - r * 0.28f, cx, cy + r * 0.12f, acc, t);
                dl.addLine(cx, cy + r * 0.12f, cx - r * 0.18f, cy + r * 0.38f, acc, t);
                dl.addLine(cx, cy + r * 0.12f, cx + r * 0.18f, cy + r * 0.38f, acc, t);
            }
            case BUILDER -> {
                dl.addCircle(cx, cy, r * 0.8f, acc, 40, t);
                dl.addEllipse(cx, cy, r * 0.32f, r * 0.8f, dim, 0, 32, t);
                dl.addLine(cx - r * 0.8f, cy, cx + r * 0.8f, cy, dim, t);
                dl.addEllipse(cx, cy - r * 0.42f, r * 0.68f, r * 0.12f, dim, 0, 32, t);
            }
            case STUDIO -> {
                float w = r * 0.55f, hh = r * 0.7f;
                float[] xs = {cx - w * 1.5f, cx - w * 0.5f, cx + w * 0.5f, cx + w * 1.5f};
                for (int i = 0; i < 3; i++) {
                    float dy = i % 2 == 0 ? 0 : r * 0.12f;
                    dl.addQuad(xs[i], cy - hh + dy, xs[i + 1], cy - hh + r * 0.12f - dy, xs[i + 1], cy + hh + r * 0.12f - dy, xs[i], cy + hh + dy, i == 1 ? acc : dim, t);
                }
                dl.addCircleFilled(cx + w * 0.1f, cy - r * 0.05f, r * 0.08f, acc);
            }
        }
    }

    // ---- doing things ----

    private void open(App a) {
        try {
            a.start(Path.of(projects.get().trim()), agentConsole, custom(a));
            lastOpened = a.id.name();
            save();
        } catch (Exception e) {
            say("couldn't open it: " + e.getMessage());
        }
    }

    private String custom(App a) { return settings.scope(a.id.name().toLowerCase()).str("command", ""); }

    private void say(String s) { message = s; messageUntil = glfwGetTime() + Math.max(4, s.length() / 20.0); }

    private void save() {
        var s = settings.scope("launcher");
        s.put("projects", projects.get().trim());
        s.put("agentConsole", agentConsole);
        s.put("textScale", textScale);
        s.put("lastOpened", lastOpened);
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
        c.add("open", "open <engine|builder|studio>", "open an app, as its card's button does", a -> {
            App app = app(a.str(0));
            open(app);
            if (!app.isRunning()) throw new IllegalStateException(message);
            return app.title + ": starting";
        });
        c.add("stop", "stop <engine|builder|studio>", "close an app the launcher opened", a -> { app(a.str(0)).stop(); return null; });
        c.add("apps", "apps", "what each app is doing", a -> {
            var b = new StringBuilder();
            for (App app : apps) b.append(String.format("%-8s %-8s %s%n", app.id.name().toLowerCase(), app.state().name().toLowerCase(), app.lastLine()));
            return b.toString().stripTrailing();
        });
        c.add("text", "text <100|125|150|200>", "the Text size, in percent", a -> { textScale = (float) Math.clamp(a.num(0) / 100, 1, 2); save(); return null; });
        c.add("window", "window <width> <height>", "resize the window", a -> { glfwSetWindowSize(window, (int) a.num(0), (int) a.num(1)); return null; });
        c.add("console", "console [on|off]", "open or close this console (` or F1)", a -> { console.setOpen(a.on(0, !console.isOpen())); return null; });
        c.add("quit", "quit", "close the launcher (the apps it opened stay open)", a -> { glfwSetWindowShouldClose(window, true); return null; });
        c.var("projects", "the folder that holds the repos", projects::get, v -> { projects.set(v); save(); });
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
