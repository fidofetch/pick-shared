package com.pick.console;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

// Lets an agent (or a script) drive a running app: it connects to 127.0.0.1:<port> and sends lines of commands,
// the same ones the console takes. Each line is answered with its output, then a last line "## ok" or "## error".
// Lines that start with "# " are pushed: watches and topics the connection asked for (docs/console.md).
//     python3 tools/console.py "settings; wait 1; shot /tmp/settings.png"
//     python3 tools/console.py -i         stay connected: type lines, see answers and pushed lines as they come
// Off unless the app is started with the port flag (-Dvoxel.console / -Dbuilder.console). Only this machine
// can connect: the socket listens on the loopback address, never on the network.
public final class CommandServer implements AutoCloseable {
    public static final String END_OK = "## ok", END_ERROR = "## error";   // the last line of each answer
    public static final String PUSH = "# ";   // starts a pushed line (a watch, a topic), never part of an answer
    public static final long TIMEOUT_S = 300;   // a line that runs longer is answered with an error (it keeps running)
    private static final int MAX_PENDING = 20000;   // pushed lines held for an agent that is not reading; more are dropped
    private static final String HANG_UP = "\u0000hang up";
    private final AtomicInteger clients = new AtomicInteger();
    private final Commands commands;
    private final ServerSocket socket;
    private final Thread thread;
    private volatile boolean closed;

    public CommandServer(Commands commands, int port) throws IOException {
        this.commands = commands;
        this.socket = new ServerSocket(port, 8, InetAddress.getLoopbackAddress());
        this.thread = new Thread(this::accept, "console-server");
        thread.setDaemon(true);
        thread.start();
    }

    public int port() { return socket.getLocalPort(); }

    // Starts the server when the flag is set: empty means the app's default port. Prints where it listens.
    public static CommandServer startIfAsked(Commands commands, String flag, int defaultPort) {
        String v = System.getProperty(flag);
        if (v == null) return null;
        try {
            CommandServer s = new CommandServer(commands, v.isBlank() ? defaultPort : Integer.parseInt(v.trim()));
            System.out.println("[console] listening on 127.0.0.1:" + s.port());
            return s;
        } catch (IOException | NumberFormatException e) {
            System.err.println("[console] could not listen (" + flag + "=" + v + "): " + e.getMessage());
            return null;
        }
    }

    private void accept() {
        while (!closed) {
            try {
                Socket s = socket.accept();
                Thread t = new Thread(() -> serve(s), "console-client");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                if (!closed) System.err.println("[console] " + e.getMessage());
            }
        }
    }

    // One connection is one session. Lines are read as they come and run in order; their answers go back in the
    // same order, while the session's pushed lines ("# watch ...", "# bake ...") go out between answers. So an agent
    // can stay connected: send "watch player.pos", then "key w 2", and read positions as it walks.
    private void serve(Socket s) {
        var outbox = new LinkedBlockingQueue<String>();
        int id = clients.incrementAndGet();
        Session session = new Session("agent " + id, true, line -> { if (outbox.size() < MAX_PENDING) outbox.add(PUSH + line); });
        Thread writer = new Thread(() -> {
            try (var out = new PrintWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8), false)) {
                for (String line; !(line = outbox.take()).equals(HANG_UP); ) {
                    out.println(line);
                    if (outbox.isEmpty()) out.flush();
                    if (out.checkError()) break;
                }
                out.flush();
            } catch (IOException | InterruptedException ignored) {
            }
        }, "console-writer");
        writer.setDaemon(true);
        writer.start();
        commands.open(session);
        try (var in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8))) {
            CompletableFuture<Void> last = CompletableFuture.completedFuture(null);
            for (String line; (line = in.readLine()) != null; ) {
                if (line.isBlank()) continue;
                var answer = commands.submit(line, session).copy().orTimeout(TIMEOUT_S, TimeUnit.SECONDS);
                // answers leave in the order the lines came, even when a timeout answers one early
                last = last.thenCompose(x -> answer.handle((text, err) -> {
                    if (err == null) { if (text != null && !text.isEmpty()) outbox.add(text); outbox.add(END_OK); }
                    else {
                        Throwable e = err instanceof CompletionException && err.getCause() != null ? err.getCause() : err;
                        outbox.add(e instanceof TimeoutException ? "error: no answer after " + TIMEOUT_S + " s (still running)" : e.getMessage());
                        outbox.add(END_ERROR);
                    }
                    return null;
                }));
            }
            last.join();   // the agent closed its side: send what is still owed
        } catch (IOException ignored) {
            // the agent hung up
        } finally {
            commands.close(session);
            outbox.add(HANG_UP);
            try { writer.join(2000); } catch (InterruptedException ignored) {}
            try { s.close(); } catch (IOException ignored) {}
        }
    }

    @Override public void close() {
        closed = true;
        try { socket.close(); } catch (IOException ignored) {}
    }
}
