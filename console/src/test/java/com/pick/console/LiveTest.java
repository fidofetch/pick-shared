package com.pick.console;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

// The live side: variables, watches, topics, aliases, and a connection that stays open
class LiveTest {

    static final class Thing { double speed = 10; boolean fly; }

    private static String run(Commands c, String line) {
        var r = c.submit(line, false);
        for (int i = 0; i < 10 && !r.isDone(); i++) c.tick(0.1);
        return r.join();
    }

    @Test void variablesReadSetAndRefuse() {
        var c = new Commands();
        var t = new Thing();
        c.num("player.speed", "m/s", () -> t.speed, v -> t.speed = Math.clamp(v, 0, 50));
        c.flag("player.fly", "flying", () -> t.fly, v -> t.fly = v);
        c.value("player.alt", "m above sea", () -> 12.34567);
        assertEquals("player.speed = 50", run(c, "set player.speed 80"));   // the setter clamps; the answer says what was kept
        assertEquals("player.fly = true", run(c, "set player.fly on"));
        assertEquals("player.alt = 12.3457\nplayer.fly = true\nplayer.speed = 50", run(c, "get player."));
        var ro = c.submit("set player.alt 3", false);
        c.tick(0);
        assertTrue(ro.isCompletedExceptionally());
        var bad = c.submit("set player.speed fast", false);
        c.tick(0);
        assertTrue(bad.isCompletedExceptionally());
        assertEquals(50, t.speed);
    }

    public enum Mode { WALK, FLY }
    public static final class Vec { public double x, y, z; }
    public static final class Knobs {
        public float sharpen = 0.3f; public int steps = 512; public boolean shadowOn = true; public Mode mode = Mode.WALK;
        public final Vec pos = new Vec(); public final int fixed = 7; public static int ignored = 1;
    }

    @Test void publicFieldsBecomeVariables() {
        var c = new Commands();
        var k = new Knobs();
        c.fields("r", k);
        assertEquals("r.fixed = 7\nr.mode = walk\nr.pos = 0,0,0\nr.shadowOn = true\nr.sharpen = 0.3\nr.steps = 512", run(c, "get r."));
        run(c, "set r.sharpen 0.5; set r.steps 100.4; set r.shadowOn off; set r.mode fly; set r.pos 1,2.5,-3");
        assertEquals(0.5f, k.sharpen);
        assertEquals(100, k.steps);
        assertFalse(k.shadowOn);
        assertEquals(Mode.FLY, k.mode);
        assertEquals(2.5, k.pos.y);
        var ro = c.submit("set r.fixed 3", false);
        c.tick(0);
        assertTrue(ro.isCompletedExceptionally());
    }

    @Test void aWatchPushesOnlyWhenAValueChanges() {
        var c = new Commands();
        var t = new Thing();
        c.num("player.speed", "", () -> t.speed, v -> t.speed = v);
        List<String> pushed = new ArrayList<>();
        var s = new Session("test", true, pushed::add);
        c.open(s);
        c.submit("watch player.speed every 0", s);
        c.tick(0.1);   // runs the line
        c.tick(0.1);   // first reading
        c.tick(0.1);   // same value: nothing
        t.speed = 3;
        c.tick(0.1);
        assertEquals(2, pushed.size(), pushed.toString());
        assertTrue(pushed.get(0).startsWith("watch t=") && pushed.get(0).endsWith(" player.speed=10"), pushed.get(0));
        assertTrue(pushed.get(1).endsWith(" player.speed=3"));
        c.close(s);
        c.tick(0.1);
        t.speed = 4;
        c.tick(0.1);
        assertEquals(2, pushed.size(), "a closed session's watch stops");
    }

    @Test void topicsGoOnlyToThoseWhoFollowThem() {
        var c = new Commands();
        c.topic("bake", "bake stages");
        List<String> a = new ArrayList<>(), b = new ArrayList<>();
        var sa = new Session("a", true, a::add);
        var sb = new Session("b", true, b::add);
        c.open(sa); c.open(sb);
        c.submit("subscribe bake", sa);
        c.tick(0);
        c.emit("bake", "done in 2 s");
        assertEquals(List.of("bake done in 2 s"), a);
        assertEquals(List.of(), b);
        var unknown = c.submit("subscribe nope", sb);
        c.tick(0);
        assertTrue(unknown.isCompletedExceptionally());
    }

    @Test void anAliasRunsItsLineWithItsWords() {
        var c = new Commands();
        var seen = new StringBuilder();
        c.add("mark", "mark <x>", "", a -> { seen.append(a.str(0)); return null; });
        run(c, "alias twice \"mark $1; mark $1\"");
        assertEquals("", run(c, "twice q; mark z"));
        assertEquals("qqz", seen.toString());
        run(c, "alias loop \"loop\"");
        var r = c.submit("loop", false);
        for (int i = 0; i < 3; i++) c.tick(0);
        assertTrue(r.isCompletedExceptionally());
        var clash = c.submit("alias echo \"mark x\"", false);
        c.tick(0);
        assertTrue(clash.isCompletedExceptionally(), "an alias cannot replace a command");
    }

    @Test void shotsComeBackOverTheConnection() throws Exception {
        var c = new Commands();
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3};
        c.screenshots(f -> {   // a pretend frame: written at once
            try { java.nio.file.Files.write(java.nio.file.Path.of(f), png); } catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
            return java.util.concurrent.CompletableFuture.completedFuture(java.nio.file.Path.of(f).toAbsolutePath().toString());
        });
        var dir = java.nio.file.Files.createTempDirectory("shots");
        List<String> pushed = new ArrayList<>();
        var s = new Session("a", true, pushed::add);
        c.open(s);
        var r = c.submit("shot " + dir.resolve("a.png") + " send", s);
        c.tick(0);
        String[] lines = r.join().split("\n");
        assertEquals(dir.resolve("a.png").toString(), lines[0]);
        assertTrue(lines[1].startsWith("png a.png "));
        assertArrayEquals(png, java.util.Base64.getDecoder().decode(lines[1].substring("png a.png ".length())));
        assertTrue(c.log().stream().allMatch(l -> l.text().length() < 450), "the picture stays out of the console's log");

        c.submit("film every 1 for 2.5", s);
        for (int i = 0; i < 30; i++) c.tick(0.1);
        assertEquals(3, pushed.size(), pushed.toString());   // at 0, 1 and 2 s
        assertTrue(pushed.get(0).startsWith("shot png film-1.png "), pushed.get(0));
        assertTrue(pushed.get(2).startsWith("shot png film-3.png "));
    }

    @Test void aSessionLetsGoWhenItEnds() {
        var c = new Commands();
        boolean[] held = {false};
        c.add("hold", "hold", "", a -> { held[0] = true; a.session().closing(() -> held[0] = false); return null; });
        var s = new Session("x", true, l -> { });
        c.open(s);
        c.submit("hold", s);
        c.tick(0);
        assertTrue(held[0]);
        c.close(s);
        c.tick(0);
        assertFalse(held[0]);
    }

    @Test void aConnectionStaysOpenAndGetsPushedLinesBetweenAnswers() throws Exception {
        var c = new Commands();
        double[] v = {1};
        c.num("x", "", () -> v[0], n -> v[0] = n);
        try (var server = new CommandServer(c, 0)) {
            var ticker = new Thread(() -> { while (!Thread.interrupted()) { c.tick(0.016); try { Thread.sleep(5); } catch (InterruptedException e) { return; } } });
            ticker.start();
            try (var sock = new Socket(InetAddress.getLoopbackAddress(), server.port());
                 var out = new PrintWriter(sock.getOutputStream(), true, StandardCharsets.UTF_8);
                 var in = new BufferedReader(new InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8))) {
                // two lines sent at once: the answers come back in order
                out.println("watch x every 0");
                out.println("wait 0.2; set x 5");
                List<String> got = new CopyOnWriteArrayList<>();
                while (got.stream().filter(l -> l.equals(CommandServer.END_OK)).count() < 2) got.add(in.readLine());
                for (String l = ""; !l.endsWith("x=5"); ) { l = in.readLine(); got.add(l); }
                List<String> answers = got.stream().filter(l -> !l.startsWith(CommandServer.PUSH)).toList();
                assertEquals(List.of("watching x (lines start with \"# watch\")", "## ok", "x = 5", "## ok"), answers);
                assertTrue(got.stream().anyMatch(l -> l.startsWith("# watch t=") && l.endsWith(" x=1")), got.toString());
                assertTrue(got.get(got.size() - 1).endsWith(" x=5"));
            } finally {
                ticker.interrupt();
            }
        }
    }
}
