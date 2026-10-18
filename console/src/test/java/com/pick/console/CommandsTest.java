package com.pick.console;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;

class CommandsTest {

    @Test void splitKeepsQuotedWords() {
        assertEquals(List.of("echo", "a b", "c"), Commands.split("echo \"a b\"  c"));
        assertEquals(List.of("shot", ""), Commands.split("shot \"\""));
        assertEquals(List.of("a", "echo \"x; y\"", "b"), Commands.parts("a; echo \"x; y\" ;b;"));
    }

    @Test void runsInOrderAndWaits() {
        var c = new Commands();
        var seen = new StringBuilder();
        c.add("mark", "mark <x>", "", a -> { seen.append(a.str(0)); return null; });
        CompletableFuture<String> r = c.submit("mark a; wait 0.5; mark b; echo done", false);
        c.tick(0.1);
        assertEquals("a", seen.toString());   // the wait holds the rest
        assertFalse(r.isDone());
        for (int i = 0; i < 5; i++) c.tick(0.1);
        assertEquals("ab", seen.toString());
        assertEquals("done", r.join());
    }

    @Test void anErrorStopsTheLineAndSaysWhy() {
        var c = new Commands();
        var seen = new StringBuilder();
        c.add("mark", "mark <x>", "", a -> { seen.append(a.str(0)); return null; });
        var r = c.submit("mark; mark b", false);
        c.tick(0.1);
        assertEquals("", seen.toString());
        var e = assertThrows(ExecutionException.class, r::get);
        assertTrue(e.getCause().getMessage().contains("missing a value"), e.getCause().getMessage());
        assertTrue(e.getCause().getMessage().contains("usage: mark <x>"));
        var u = c.submit("nope", false);
        c.tick(0.1);
        assertTrue(u.isCompletedExceptionally());
    }

    @Test void argsReadOnOffNumbersAndEnums() {
        var c = new Commands();
        enum Fruit { APPLE, PEAR }
        c.add("t", "t", "", a -> a.on(0, false) + " " + a.num(1) + " " + a.pick(2, Fruit.class));
        var r = c.submit("t on 2.5 pear", false);
        c.tick(0);
        assertEquals("true 2.5 PEAR", r.join());
        var bad = c.submit("t maybe 1 pear", false);
        c.tick(0);
        assertTrue(bad.isCompletedExceptionally());
    }

    @Test void framesWaitsAndAFutureCanChain() {
        var c = new Commands();
        int[] n = {0};
        c.add("count", "count", "", a -> { n[0]++; return null; });
        var r = c.submit("frames 3; count", false);
        c.tick(0); c.tick(0);
        assertEquals(0, n[0]);
        c.tick(0); c.tick(0);
        assertEquals(1, n[0]);
        assertTrue(r.isDone());
    }

    @Test void theServerAnswersOnLoopback() throws Exception {
        var c = new Commands();
        try (var s = new CommandServer(c, 0)) {
            var ticker = new Thread(() -> { while (!Thread.interrupted()) { c.tick(0.016); try { Thread.sleep(5); } catch (InterruptedException e) { return; } } });
            ticker.start();
            try (var sock = new Socket(InetAddress.getLoopbackAddress(), s.port());
                 var out = new PrintWriter(sock.getOutputStream(), true, StandardCharsets.UTF_8);
                 var in = new BufferedReader(new InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8))) {
                out.println("echo hello");
                assertEquals("hello", in.readLine());
                assertEquals(CommandServer.END_OK, in.readLine());
                out.println("nope");
                assertTrue(in.readLine().startsWith("error: no command"));
                assertEquals(CommandServer.END_ERROR, in.readLine());
            } finally {
                ticker.interrupt();
            }
        }
    }
}
