package com.pick.console;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

// Lets an agent (or a script) drive a running app: it connects to 127.0.0.1:<port> and sends lines of commands,
// the same ones the console takes. Each line is answered with its output, then a last line "## ok" or "## error".
//     python3 tools/console.py "settings; wait 1; shot /tmp/settings.png"
// Off unless the app is started with the port flag (-Dvoxel.console / -Dbuilder.console). Only this machine
// can connect: the socket listens on the loopback address, never on the network.
public final class CommandServer implements AutoCloseable {
    public static final String END_OK = "## ok", END_ERROR = "## error";   // the last line of each answer
    public static final long TIMEOUT_S = 300;   // a line that runs longer is answered with an error (it keeps running)
    private final Commands commands;
    private final ServerSocket socket;
    private final Thread thread;
    private volatile boolean closed;

    public CommandServer(Commands commands, int port) throws IOException {
        this.commands = commands;
        this.socket = new ServerSocket(port, 4, InetAddress.getLoopbackAddress());
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

    private void serve(Socket s) {
        try (s; var in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
             var out = new PrintWriter(s.getOutputStream(), true, StandardCharsets.UTF_8)) {
            for (String line; (line = in.readLine()) != null; ) {
                if (line.isBlank()) continue;
                String text; boolean ok;
                try {
                    text = commands.submit(line, true).get(TIMEOUT_S, TimeUnit.SECONDS);
                    ok = true;
                } catch (java.util.concurrent.ExecutionException e) {
                    text = e.getCause().getMessage(); ok = false;
                } catch (java.util.concurrent.TimeoutException e) {
                    text = "error: no answer after " + TIMEOUT_S + " s (still running)"; ok = false;
                } catch (InterruptedException e) {
                    return;
                }
                if (text != null && !text.isEmpty()) out.println(text);
                out.println(ok ? END_OK : END_ERROR);
            }
        } catch (IOException ignored) {
            // the agent hung up
        }
    }

    @Override public void close() {
        closed = true;
        try { socket.close(); } catch (IOException ignored) {}
    }
}
