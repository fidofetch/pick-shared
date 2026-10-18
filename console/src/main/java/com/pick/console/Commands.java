package com.pick.console;

import com.pick.settings.SettingsPage;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;

// The commands an app answers to, typed in its console (ConsoleWindow) or sent by an agent (CommandServer).
// One line can hold several commands split by ';', run in order: "settings; wait 1; shot a.png".
// Everything runs on the app's main thread, in tick(), so a command may touch the game freely.
//     commands.add("hud", "hud [on|off]", "show or hide the debug HUD", a -> { hud.show(a.on(0, !hud.isVisible())); return null; });
//     commands.tick(dt)        once a frame, after drawing
// A command returns its output (a String, or null for none), or a CompletableFuture<String> when it ends on a
// later frame (a screenshot is saved at the end of a frame; "wait" ends after some seconds).
//
// The live side, for an agent that stays connected (docs/console.md):
//     commands.num("player.speed", "walking speed, m/s", () -> p.speed, v -> p.speed = (float) v);   a variable
//     commands.topic("bake", "a line when the bricks around the camera are baked");  commands.emit("bake", "...");
// Variables are read and changed with get/set and followed with watch; topics are followed with subscribe.
// Lines for a session (watch output, topics it follows) are pushed to it as they happen, between answers.
public final class Commands {

    @FunctionalInterface
    public interface Handler { Object run(Args a) throws Exception; }

    public record Command(String name, String usage, String help, Handler handler) {}

    // A value the app lets agents read and (unless set is null) change. set throws to refuse a value.
    public record Var(String name, String help, Supplier<String> get, Consumer<String> set) {
        public boolean readOnly() { return set == null; }
    }

    // A family of variables kept elsewhere, such as the saved settings: names it has now, and a writer that
    // answers with the value kept (it may clamp). owns(name) says a name is its own even before it has a value.
    public interface VarSource {
        Map<String, String> values();
        boolean owns(String name);
        String set(String name, String value);
    }

    // A handler that returns this has its line run in its place (an alias)
    public record Expand(String line) {}

    private final Map<String, Command> commands = new TreeMap<>();
    private final ConcurrentLinkedQueue<Job> queue = new ConcurrentLinkedQueue<>();
    private final List<Timer> timers = new ArrayList<>();
    private final List<Line> log = new ArrayList<>();
    private final Map<String, Var> vars = new TreeMap<>();
    private final List<VarSource> sources = new ArrayList<>();
    private final Map<String, String> topics = new TreeMap<>();      // name -> help
    private final Map<String, String> aliases = new TreeMap<>();
    private final List<Session> sessions = new CopyOnWriteArrayList<>();
    private final ConcurrentLinkedQueue<Runnable> onMain = new ConcurrentLinkedQueue<>();
    private final List<Watch> watches = new ArrayList<>();
    private final Session local = new Session("console", false, s -> log(s, Kind.OUTPUT, false));
    private final Session anonymous = new Session("agent", true, s -> { });   // lines sent with no connection (-D...run)
    private Job current;
    private double now;
    private long frame;
    public static final int LOG_LINES = 400;

    // One line of the console's history; agent: sent by an agent, not typed
    public record Line(String text, Kind kind) {}
    public enum Kind { INPUT, AGENT_INPUT, OUTPUT, ERROR }

    public Commands() {
        add("help", "help [command]", "list the commands, or show how to use one", a -> {
            if (a.size() > 0) {
                Command c = commands.get(a.str(0));
                if (c == null) throw new IllegalArgumentException("no command " + a.str(0));
                return c.usage() + "\n  " + c.help();
            }
            StringBuilder b = new StringBuilder();
            for (Command c : commands.values()) b.append(String.format("%-34s %s%n", c.usage(), c.help()));
            return b.toString().stripTrailing();
        });
        add("wait", "wait <seconds>", "wait before the next command on the line (lets a change settle before a shot)",
                a -> after(a.num(0)));
        add("frames", "frames <n>", "wait n frames", a -> afterFrames((int) a.num(0)));
        add("echo", "echo <text>", "print the text (marks a point in a script)", a -> a.rest(0));

        add("get", "get [name or start of names...]", "variables' values: all, one, or those that start with each word", a -> {
            var m = new LinkedHashMap<String, String>();
            if (a.size() == 0) m.putAll(values(""));
            for (int i = 0; i < a.size(); i++) {
                var found = values(a.str(i));
                if (found.isEmpty()) throw new IllegalArgumentException("no variable \"" + a.str(i) + "\" (vars lists them)");
                m.putAll(found);
            }
            var b = new StringBuilder();
            m.forEach((k, v) -> b.append(k).append(" = ").append(v).append('\n'));
            return b.toString().stripTrailing();
        });
        add("set", "set <name> <value>", "change a variable (vars lists them); answers with the value kept", a ->
                a.str(0) + " = " + write(a.str(0), a.rest(1)));
        add("vars", "vars [start of names]", "the variables with what they are; (read only) cannot be set", a -> {
            String w = a.str(0, "");
            var b = new StringBuilder();
            for (Var v : vars.values()) if (v.name().startsWith(w))
                b.append(String.format("%-28s %-14s %s%s%n", v.name(), safeGet(v), v.help(), v.readOnly() ? " (read only)" : ""));
            for (VarSource src : sources)
                src.values().forEach((k, val) -> { if (k.startsWith(w) && !vars.containsKey(k)) b.append(String.format("%-28s %s%n", k, val)); });
            return b.length() == 0 ? "no variable starts with \"" + w + "\"" : b.toString().stripTrailing();
        });
        add("watch", "watch [names...] [every <s>] [always]",
                "push \"watch t=... name=value ...\" to you every s (default 0.25) when a value changed (always: every time); no names lists your watches", a -> {
            Session s = a.session();
            if (a.size() == 0) {
                var b = new StringBuilder();
                for (Watch w : watches) if (w.session == s) b.append(String.join(" ", w.names)).append("  every ").append(fmt(w.every)).append(w.always ? " always" : "").append('\n');
                return b.length() == 0 ? "no watches (watch <names> starts one)" : b.toString().stripTrailing();
            }
            List<String> names = new ArrayList<>();
            double every = 0.25; boolean always = false;
            for (int i = 0; i < a.size(); i++) {
                String w = a.str(i);
                if (w.equals("every")) { every = Math.max(0, a.num(++i)); }
                else if (w.equals("always")) always = true;
                else { if (values(w).isEmpty() && !owned(w)) throw new IllegalArgumentException("no variable \"" + w + "\" (vars lists them)"); names.add(w); }
            }
            if (names.isEmpty()) throw new IllegalArgumentException("name a variable to watch");
            Watch w = new Watch(s, names, every, always);
            watches.add(w);
            return "watching " + String.join(" ", names) + (s == local ? "" : " (lines start with \"# watch\")");
        });
        add("unwatch", "unwatch [name]", "stop your watches, or the ones that name this variable", a -> {
            String n = a.str(0, null);
            int before = watches.size();
            watches.removeIf(w -> w.session == a.session() && (n == null || w.names.contains(n)));
            return (before - watches.size()) + " stopped";
        });
        add("subscribe", "subscribe [topic...]", "have a topic's lines pushed to you as they happen; no topic lists them", a -> {
            Session s = a.session();
            if (a.size() == 0) {
                var b = new StringBuilder();
                topics.forEach((t, h) -> b.append(String.format("%s %-10s %s%n", s.follows(t) ? "*" : " ", t, h)));
                return b.toString().stripTrailing();
            }
            for (int i = 0; i < a.size(); i++) {
                if (!topics.containsKey(a.str(i))) throw new IllegalArgumentException("no topic \"" + a.str(i) + "\"; the topics are " + String.join(", ", topics.keySet()));
                s.topics.add(a.str(i));
            }
            return "following " + String.join(" ", s.topics);
        });
        add("unsubscribe", "unsubscribe [topic...]", "stop a topic's lines (all of them when none is named)", a -> {
            if (a.size() == 0) a.session().topics.clear();
            for (int i = 0; i < a.size(); i++) a.session().topics.remove(a.str(i));
            return a.session().topics.isEmpty() ? "following nothing" : "following " + String.join(" ", a.session().topics);
        });
        add("alias", "alias [name] [\"line\"]",
                "make a command of your own that runs a line ($1.. its words, $* all of them); no line shows it, no name lists them", a -> {
            if (a.size() == 0) {
                var b = new StringBuilder();
                aliases.forEach((k, v) -> b.append(k).append(" = ").append(v).append('\n'));
                return b.length() == 0 ? "no aliases" : b.toString().stripTrailing();
            }
            String name = a.str(0).toLowerCase();
            if (a.size() == 1) {
                if (!aliases.containsKey(name)) throw new IllegalArgumentException("no alias " + name);
                return name + " = " + aliases.get(name);
            }
            if (commands.containsKey(name) && !aliases.containsKey(name)) throw new IllegalArgumentException(name + " is already a command");
            String line = a.rest(1);
            aliases.put(name, line);
            add(name, name + " ...", "alias: " + line, b -> new Expand(expand(line, b)));
            return null;
        });
        add("unalias", "unalias <name>", "remove an alias", a -> {
            String name = a.str(0).toLowerCase();
            if (aliases.remove(name) == null) throw new IllegalArgumentException("no alias " + name);
            commands.remove(name);
            return null;
        });
        value("time", "seconds since the app started (frames of tick)", () -> now);
        value("frame", "frames since the app started", () -> frame);
        topic("log", "every line the console shows, as it shows it (> typed, agent> sent by an agent)");
    }

    // $1..$9 are the alias's words, $* all of them
    private static String expand(String line, Args a) {
        String out = line.replace("$*", a.rest(0));
        for (int i = 9; i >= 1; i--) out = out.replace("$" + i, a.str(i - 1, ""));
        return out;
    }

    public void add(String name, String usage, String help, Handler h) { commands.put(name, new Command(name, usage, help, h)); }
    public Map<String, Command> all() { return commands; }

    // --- variables ---

    public void var(String name, String help, Supplier<?> get, Consumer<String> set) {
        vars.put(name, new Var(name, help, () -> fmt(get.get()), set));
    }
    public void value(String name, String help, Supplier<?> get) { var(name, help, get, null); }
    public void num(String name, String help, DoubleSupplier get, DoubleConsumer set) {
        var(name, help, get::getAsDouble, set == null ? null : v -> set.accept(number(v)));
    }
    public void flag(String name, String help, BooleanSupplier get, Consumer<Boolean> set) {
        var(name, help, get::getAsBoolean, set == null ? null : v -> set.accept(onOff(v)));
    }
    public <E extends Enum<E>> void choice(String name, String help, Class<E> type, Supplier<E> get, Consumer<E> set) {
        var(name, help, get, set == null ? null : v -> set.accept(pick(v, type)));
    }
    public void vars(VarSource source) { sources.add(source); }

    // Every public field of an object as a variable "prefix.field": numbers, booleans, enums and strings, and
    // vectors (an object with public number fields x, y, z, written "x,y,z"). Final ones are read only, as are
    // vectors held in final fields (the vector itself can still be set). Fields added later show up by themselves.
    //     commands.fields("render", renderer.settings());     render.sharpen, render.steps, render.shadowOn, ...
    public void fields(String prefix, Object target) {
        for (java.lang.reflect.Field f : target.getClass().getFields()) {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
            boolean fin = java.lang.reflect.Modifier.isFinal(f.getModifiers());
            Class<?> t = f.getType();
            String name = prefix + "." + f.getName();
            String kind = t.isEnum() ? "one of " + enumNames(t) : t.getSimpleName();
            if (t == double.class || t == float.class || t == int.class || t == long.class || t == boolean.class
                    || t == String.class || t.isEnum()) {
                var(name, kind, () -> field(f, target), fin ? null : v -> setField(f, target, v));
            } else if (vectorFields(t) != null) {
                var(name, "vector x,y,z", () -> vectorText(field(f, target)), v -> setVector(field(f, target), v));
            }
        }
    }

    private static Object field(java.lang.reflect.Field f, Object o) {
        try { return f.get(o); } catch (IllegalAccessException e) { throw new IllegalStateException(e); }
    }
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void setField(java.lang.reflect.Field f, Object o, String v) {
        Class<?> t = f.getType();
        Object val;
        if (t == double.class) val = number(v);
        else if (t == float.class) val = (float) number(v);
        else if (t == int.class) val = (int) Math.round(number(v));
        else if (t == long.class) val = Math.round(number(v));
        else if (t == boolean.class) val = onOff(v);
        else if (t.isEnum()) val = pick(v, (Class) t);
        else val = v;
        try { f.set(o, val); } catch (IllegalAccessException e) { throw new IllegalStateException(e); }
    }
    private static String enumNames(Class<?> t) {
        StringBuilder b = new StringBuilder();
        for (Object e : t.getEnumConstants()) b.append(b.length() > 0 ? ", " : "").append(((Enum<?>) e).name().toLowerCase());
        return b.toString();
    }
    private static java.lang.reflect.Field[] vectorFields(Class<?> t) {
        try {
            var xyz = new java.lang.reflect.Field[]{t.getField("x"), t.getField("y"), t.getField("z")};
            for (var f : xyz) if (f.getType() != double.class && f.getType() != float.class) return null;
            return xyz;
        } catch (NoSuchFieldException e) { return null; }
    }
    private static String vectorText(Object v) {
        var xyz = vectorFields(v.getClass());
        return fmt(field(xyz[0], v)) + "," + fmt(field(xyz[1], v)) + "," + fmt(field(xyz[2], v));
    }
    // "x,y,z" or "x y z"
    public static double[] vector(String s) {
        String[] p = s.trim().split("[,\\s]+");
        if (p.length != 3) throw new IllegalArgumentException("a vector is x,y,z, not \"" + s + "\"");
        return new double[]{number(p[0]), number(p[1]), number(p[2])};
    }
    private static void setVector(Object v, String s) {
        double[] n = vector(s);
        var xyz = vectorFields(v.getClass());
        for (int i = 0; i < 3; i++) {
            try { if (xyz[i].getType() == double.class) xyz[i].setDouble(v, n[i]); else xyz[i].setFloat(v, (float) n[i]); }
            catch (IllegalAccessException e) { throw new IllegalStateException(e); }
        }
    }

    // What the app prints (System.out) also goes out on the topic "out", line by line, and still to the terminal
    public void mirrorStdout() {
        if (topics.containsKey("out")) return;
        topic("out", "what the app prints to its terminal ([bake] ..., [world] ...)");
        java.io.PrintStream term = System.out;
        var tee = new java.io.OutputStream() {
            private final java.io.ByteArrayOutputStream line = new java.io.ByteArrayOutputStream();
            @Override public synchronized void write(int b) {
                term.write(b);
                if (b == '\n') { emit("out", line.toString(java.nio.charset.StandardCharsets.UTF_8).stripTrailing()); line.reset(); }
                else if (line.size() < 8192) line.write(b);
            }
            @Override public synchronized void write(byte[] b, int off, int len) { for (int i = 0; i < len; i++) write(b[off + i]); }
            @Override public void flush() { term.flush(); }
        };
        System.setOut(new java.io.PrintStream(tee, true, java.nio.charset.StandardCharsets.UTF_8));
    }

    // The saved settings as variables: "sky.haze" and the like. A set is checked and clamped by its section and saved.
    public void settings(SettingsPage page) {
        vars(new VarSource() {
            public Map<String, String> values() { return page.file().all(); }
            public boolean owns(String name) { return page.owns(name); }
            public String set(String name, String value) { return page.set(name, value); }
        });
    }

    public Map<String, Var> variables() { return vars; }

    // The variables that are `w` or start with it, name -> value
    public Map<String, String> values(String w) {
        var m = new TreeMap<String, String>();
        Var exact = vars.get(w);
        if (exact != null) { m.put(w, safeGet(exact)); return m; }
        for (Var v : vars.values()) if (v.name().startsWith(w)) m.put(v.name(), safeGet(v));
        for (VarSource src : sources) src.values().forEach((k, val) -> { if (k.startsWith(w) && !m.containsKey(k)) m.put(k, val); });
        return m;
    }

    public String read(String name) {
        Var v = vars.get(name);
        if (v != null) return v.get().get();
        for (VarSource src : sources) { String val = src.values().get(name); if (val != null) return val; }
        throw new IllegalArgumentException("no variable \"" + name + "\" (vars lists them)");
    }

    // Changes a variable; answers with the value it kept
    public String write(String name, String value) {
        Var v = vars.get(name);
        if (v != null) {
            if (v.readOnly()) throw new IllegalArgumentException(name + " is read only");
            v.set().accept(value.trim());
            return v.get().get();
        }
        for (VarSource src : sources) if (src.owns(name)) return src.set(name, value.trim());
        throw new IllegalArgumentException("no variable \"" + name + "\" (vars lists them)");
    }

    private boolean owned(String name) { for (VarSource src : sources) if (src.owns(name)) return true; return false; }

    private static String safeGet(Var v) {
        try { return v.get().get(); } catch (RuntimeException e) { return "?"; }
    }

    // Numbers short and plain: 10, 0.25, 1.234e+07
    public static String fmt(Object o) {
        if (o instanceof Float f) o = (double) f;
        if (o instanceof Double d) {
            double v = d;
            if (Double.isNaN(v) || Double.isInfinite(v)) return String.valueOf(v);
            if (v != 0 && (Math.abs(v) >= 1e7 || Math.abs(v) < 1e-3)) return String.format(java.util.Locale.ROOT, "%.4g", v);
            return BigDecimal.valueOf(Math.round(v * 1e4) / 1e4).stripTrailingZeros().toPlainString();
        }
        if (o instanceof Enum<?> e) return e.name().toLowerCase();
        return String.valueOf(o);
    }

    static double number(String s) {
        try { return Double.parseDouble(s.trim()); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("\"" + s + "\" is not a number"); }
    }
    static boolean onOff(String s) {
        return switch (s.trim().toLowerCase()) {
            case "on", "true", "yes", "1" -> true;
            case "off", "false", "no", "0" -> false;
            default -> throw new IllegalArgumentException("say on or off, not \"" + s + "\"");
        };
    }
    static <E extends Enum<E>> E pick(String s, Class<E> type) {
        for (E e : type.getEnumConstants()) if (e.name().equalsIgnoreCase(s.trim())) return e;
        StringBuilder b = new StringBuilder();
        for (E e : type.getEnumConstants()) b.append(b.length() > 0 ? ", " : "").append(e.name().toLowerCase());
        throw new IllegalArgumentException("\"" + s + "\" is not one of " + b);
    }

    // --- topics and sessions ---

    // A kind of line an agent can follow (subscribe), such as a run's progress
    public void topic(String name, String help) { topics.put(name, help); }

    // From any thread: send a line on a topic to every session that follows it
    public void emit(String topic, String text) {
        for (Session s : sessions) if (s.follows(topic)) s.push(topic, text);
        if (local.follows(topic)) onMain.add(() -> local.push(topic, text));
    }

    // From any thread: a session starts (an agent connected) or ends (hung up). Its watches stop, and what it held
    // is let go, on the main thread.
    public void open(Session s) { sessions.add(s); }
    public void close(Session s) {
        s.closed = true;
        sessions.remove(s);
        onMain.add(() -> {
            watches.removeIf(w -> w.session == s);
            for (Runnable r : s.onClose) { try { r.run(); } catch (RuntimeException e) { print("error: " + e.getMessage()); } }
            s.onClose.clear();
        });
    }

    // From any thread: queue a line to run on the main thread; the future gets its whole output
    public CompletableFuture<String> submit(String line, boolean agent) { return submit(line, agent ? anonymous : local); }
    public CompletableFuture<String> submit(String line, Session from) {
        Job j = new Job(line, from);
        queue.add(j);
        return j.result;
    }

    // Main thread, once a frame: starts queued lines and advances the one running
    public void tick(double dt) {
        now += dt;
        frame++;
        for (Runnable r; (r = onMain.poll()) != null; ) r.run();
        for (Watch w : watches) w.tick();
        List<Timer> due = new ArrayList<>();   // completed after the sweep: a completion may add a timer
        timers.removeIf(t -> {
            if (now < t.at && frame < t.frame) return false;
            due.add(t);
            return true;
        });
        for (Timer t : due) t.done.complete(null);
        // Several commands can finish in one frame; stop at one that has to wait
        for (int guard = 0; guard < 64; guard++) {
            if (current == null) {
                current = queue.poll();
                if (current == null) return;
                log(current.line, current.from.agent ? Kind.AGENT_INPUT : Kind.INPUT);
            }
            if (current.waiting != null) {
                if (!current.waiting.isDone()) return;
                try { current.out(String.valueOf(current.waiting.join())); }
                catch (Exception e) { current.fail(cause(e)); }
                current.waiting = null;
            }
            if (current.failed || current.next >= current.parts.size()) { current.finish(); current = null; continue; }
            run(current, current.parts.get(current.next++));
        }
    }

    private void run(Job j, String text) {
        List<String> words = split(text);
        if (words.isEmpty()) return;
        Command c = commands.get(words.get(0).toLowerCase());
        if (c == null) { j.fail("no command \"" + words.get(0) + "\" (help lists them)"); return; }
        try {
            Object r = c.handler.run(new Args(c, words.subList(1, words.size()), j.from));
            if (r instanceof Expand e) {
                if (++j.expanded > 64) throw new IllegalStateException("aliases run each other in a loop");
                j.parts.addAll(j.next, parts(e.line()));
            }
            else if (r instanceof CompletableFuture<?> f) j.waiting = f;
            else if (r != null) j.out(r.toString());
        } catch (Exception e) {
            j.fail(cause(e) + "\n  usage: " + c.usage());
        }
    }

    private static String cause(Throwable e) {
        while (e.getCause() != null && (e instanceof java.util.concurrent.CompletionException)) e = e.getCause();
        String m = e.getMessage();
        return m == null || m.isBlank() ? e.getClass().getSimpleName() : m;
    }

    // A future that ends after `seconds` of frames
    public CompletableFuture<String> after(double seconds) {
        Timer t = new Timer(now + seconds, Long.MAX_VALUE);
        timers.add(t);
        return t.done;
    }

    public CompletableFuture<String> afterFrames(int n) {
        Timer t = new Timer(Double.MAX_VALUE, frame + Math.max(1, n));
        timers.add(t);
        return t.done;
    }

    // The console's history, oldest first (main thread only)
    public List<Line> log() { return log; }
    public void clearLog() { log.clear(); }
    public void print(String s) { log(s, Kind.OUTPUT); }   // a note in the console that is no command's answer

    private void log(String text, Kind kind) { log(text, kind, true); }
    private void log(String text, Kind kind, boolean emit) {
        for (String s : text.split("\n", -1)) {
            log.add(new Line(s, kind));
            if (emit) emit("log", (kind == Kind.INPUT ? "> " : kind == Kind.AGENT_INPUT ? "agent> " : "") + s);
        }
        while (log.size() > LOG_LINES) log.remove(0);
    }

    // Words split on spaces; "double quotes" keep spaces in one word
    static List<String> split(String s) {
        List<String> out = new ArrayList<>();
        StringBuilder w = new StringBuilder();
        boolean quoted = false, any = false;
        for (char ch : s.toCharArray()) {
            if (ch == '"') { quoted = !quoted; any = true; }
            else if (Character.isWhitespace(ch) && !quoted) { if (any) out.add(w.toString()); w.setLength(0); any = false; }
            else { w.append(ch); any = true; }
        }
        if (any) out.add(w.toString());
        return out;
    }

    // ';' ends a command, except inside quotes
    static List<String> parts(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder p = new StringBuilder();
        boolean quoted = false;
        for (char ch : line.toCharArray()) {
            if (ch == '"') quoted = !quoted;
            if (ch == ';' && !quoted) { out.add(p.toString().trim()); p.setLength(0); }
            else p.append(ch);
        }
        out.add(p.toString().trim());
        out.removeIf(String::isEmpty);
        return out;
    }

    private static final class Timer {
        final double at; final long frame; final CompletableFuture<String> done = new CompletableFuture<>();
        Timer(double at, long frame) { this.at = at; this.frame = frame; }
    }

    // One watch: the names (or starts of names) it reads, pushed to its session when they change
    private final class Watch {
        final Session session; final List<String> names; final double every; final boolean always;
        double nextAt; String last;
        Watch(Session s, List<String> names, double every, boolean always) { session = s; this.names = names; this.every = every; this.always = always; nextAt = now; }
        void tick() {
            if (now < nextAt) return;
            nextAt = now + every;
            var m = new LinkedHashMap<String, String>();
            for (String n : names) m.putAll(values(n));
            var b = new StringBuilder();
            m.forEach((k, v) -> b.append(' ').append(k).append('=').append(v.contains(" ") ? "\"" + v + "\"" : v));
            String line = b.toString();
            if (!always && line.equals(last)) return;
            last = line;
            session.push("watch", String.format(java.util.Locale.ROOT, "t=%.2f", now) + line);
        }
    }

    private final class Job {
        final String line; final Session from; final List<String> parts;
        final StringBuilder out = new StringBuilder();
        final CompletableFuture<String> result = new CompletableFuture<>();
        CompletableFuture<?> waiting;
        int next, expanded;
        boolean failed;

        Job(String line, Session from) { this.line = line.trim(); this.from = from; this.parts = parts(line); }

        void out(String s) {
            if (s == null || s.equals("null") || s.isEmpty()) return;
            log(s, Kind.OUTPUT);
            if (out.length() > 0) out.append('\n');
            out.append(s);
        }
        void fail(String msg) {
            failed = true;
            log("error: " + msg, Kind.ERROR);
            if (out.length() > 0) out.append('\n');
            out.append("error: ").append(msg);
        }
        void finish() {
            if (failed) result.completeExceptionally(new CommandError(out.toString()));
            else result.complete(out.toString());
        }
    }

    // A line that failed; the message is its whole output, the error last
    public static final class CommandError extends RuntimeException {
        public CommandError(String output) { super(output, null, false, false); }
    }

    // A command's words after its name, with readers that say what was wrong
    public static final class Args {
        private final Command c;
        private final List<String> w;
        private final Session session;
        Args(Command c, List<String> w, Session session) { this.c = c; this.w = w; this.session = session; }

        // Who sent the line: the console, or an agent's connection (to push it lines, or undo things when it ends)
        public Session session() { return session; }

        public int size() { return w.size(); }
        public boolean has(int i) { return i < w.size(); }
        public String str(int i) {
            if (i >= w.size()) throw new IllegalArgumentException("missing a value");
            return w.get(i);
        }
        public String str(int i, String def) { return has(i) ? w.get(i) : def; }
        public double num(int i) { return number(str(i)); }
        public double num(int i, double def) { return has(i) ? num(i) : def; }
        // on/off, true/false, yes/no, 1/0; `def` when missing (a toggle passes the opposite of the current state)
        public boolean on(int i, boolean def) {
            return has(i) ? onOff(w.get(i)) : def;
        }
        // The words from i on, joined by spaces
        public String rest(int i) { return i >= w.size() ? "" : String.join(" ", w.subList(i, w.size())); }
        // An enum constant by name, any case; the error lists the choices
        public <E extends Enum<E>> E pick(int i, Class<E> type) { return Commands.pick(str(i), type); }
    }
}
