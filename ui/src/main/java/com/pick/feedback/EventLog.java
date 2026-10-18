package com.pick.feedback;

// Where the app reports what happened: EventLog.event("planet.open orrin"). The last RECENT events are kept,
// with their time, so a feedback note or a crash report can say what happened just before. Safe from any thread.
public final class EventLog {
    private EventLog() {}

    private static final int RECENT = 50;
    private static final java.util.ArrayDeque<String> recent = new java.util.ArrayDeque<>();
    private static final long T0 = System.nanoTime();

    public static void event(String message) {
        synchronized (recent) {
            if (recent.size() == RECENT) recent.removeFirst();
            recent.addLast(String.format(java.util.Locale.ROOT, "%8.1f s  %s", (System.nanoTime() - T0) / 1e9, message));
        }
    }

    // The last events, oldest first
    public static java.util.List<String> recent() {
        synchronized (recent) { return new java.util.ArrayList<>(recent); }
    }
}
