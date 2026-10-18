package com.pick.console;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;

// The commands an app answers to, typed in its console (ConsoleWindow) or sent by an agent (CommandServer).
// One line can hold several commands split by ';', run in order: "settings; wait 1; shot a.png".
// Everything runs on the app's main thread, in tick(), so a command may touch the game freely.
//     commands.add("hud", "hud [on|off]", "show or hide the debug HUD", a -> { hud.show(a.on(0, !hud.isVisible())); return null; });
//     commands.tick(dt)        once a frame, after drawing
// A command returns its output (a String, or null for none), or a CompletableFuture<String> when it ends on a
// later frame (a screenshot is saved at the end of a frame; "wait" ends after some seconds).
public final class Commands {

    @FunctionalInterface
    public interface Handler { Object run(Args a) throws Exception; }

    public record Command(String name, String usage, String help, Handler handler) {}

    private final Map<String, Command> commands = new TreeMap<>();
    private final ConcurrentLinkedQueue<Job> queue = new ConcurrentLinkedQueue<>();
    private final List<Timer> timers = new ArrayList<>();
    private final List<Line> log = new ArrayList<>();
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
    }

    public void add(String name, String usage, String help, Handler h) { commands.put(name, new Command(name, usage, help, h)); }
    public Map<String, Command> all() { return commands; }

    // From any thread: queue a line to run on the main thread; the future gets its whole output
    public CompletableFuture<String> submit(String line, boolean agent) {
        Job j = new Job(line, agent);
        queue.add(j);
        return j.result;
    }

    // Main thread, once a frame: starts queued lines and advances the one running
    public void tick(double dt) {
        now += dt;
        frame++;
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
                log(current.line, current.agent ? Kind.AGENT_INPUT : Kind.INPUT);
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
            Object r = c.handler.run(new Args(c, words.subList(1, words.size())));
            if (r instanceof CompletableFuture<?> f) j.waiting = f;
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

    private void log(String text, Kind kind) {
        for (String s : text.split("\n", -1)) log.add(new Line(s, kind));
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

    private final class Job {
        final String line; final boolean agent; final List<String> parts;
        final StringBuilder out = new StringBuilder();
        final CompletableFuture<String> result = new CompletableFuture<>();
        CompletableFuture<?> waiting;
        int next;
        boolean failed;

        Job(String line, boolean agent) { this.line = line.trim(); this.agent = agent; this.parts = parts(line); }

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
        Args(Command c, List<String> w) { this.c = c; this.w = w; }

        public int size() { return w.size(); }
        public boolean has(int i) { return i < w.size(); }
        public String str(int i) {
            if (i >= w.size()) throw new IllegalArgumentException("missing a value");
            return w.get(i);
        }
        public String str(int i, String def) { return has(i) ? w.get(i) : def; }
        public double num(int i) {
            String s = str(i);
            try { return Double.parseDouble(s); }
            catch (NumberFormatException e) { throw new IllegalArgumentException("\"" + s + "\" is not a number"); }
        }
        public double num(int i, double def) { return has(i) ? num(i) : def; }
        // on/off, true/false, yes/no, 1/0; `def` when missing (a toggle passes the opposite of the current state)
        public boolean on(int i, boolean def) {
            if (!has(i)) return def;
            return switch (w.get(i).toLowerCase()) {
                case "on", "true", "yes", "1" -> true;
                case "off", "false", "no", "0" -> false;
                default -> throw new IllegalArgumentException("say on or off, not \"" + w.get(i) + "\"");
            };
        }
        // The words from i on, joined by spaces
        public String rest(int i) { return i >= w.size() ? "" : String.join(" ", w.subList(i, w.size())); }
        // An enum constant by name, any case; the error lists the choices
        public <E extends Enum<E>> E pick(int i, Class<E> type) {
            String s = str(i);
            for (E e : type.getEnumConstants()) if (e.name().equalsIgnoreCase(s)) return e;
            StringBuilder b = new StringBuilder();
            for (E e : type.getEnumConstants()) b.append(b.length() > 0 ? ", " : "").append(e.name().toLowerCase());
            throw new IllegalArgumentException("\"" + s + "\" is not one of " + b);
        }
    }
}
