package com.pick.console;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

// Who sent a line: the console's own command line, or one agent connection. A session can follow topics
// (subscribe) and watch values, and gets those lines pushed to it between answers. When it ends (the agent hangs
// up), what it set going is undone: keys it held are let go, its watches stop.
public final class Session {
    final String name;
    final boolean agent;
    private final Consumer<String> sink;
    final Set<String> topics = ConcurrentHashMap.newKeySet();
    final List<Runnable> onClose = new ArrayList<>();   // main thread only
    volatile boolean closed;

    // sink takes each pushed line, already prefixed ("watch t=1.2 player.speed=10"); it must not block
    public Session(String name, boolean agent, Consumer<String> sink) {
        this.name = name; this.agent = agent; this.sink = sink;
    }

    public String name() { return name; }
    public boolean agent() { return agent; }
    public boolean follows(String topic) { return topics.contains(topic); }

    // A line on a topic, for this session only (watch output goes this way)
    public void push(String topic, String text) {
        if (closed) return;
        for (String s : text.split("\n", -1)) sink.accept(topic + " " + s);
    }

    // Main thread: run when the session ends (a command that holds something for this session lets go here)
    public void closing(Runnable r) { onClose.add(r); }
}
