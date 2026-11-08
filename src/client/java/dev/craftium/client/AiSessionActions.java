package dev.craftium.client;

import java.util.*;

/**
 * Actions on several AI sessions at once. Applying them one by one, because the server writes one build at a time: each waits until none is
 * being applied, and the queue goes on after the screen that started it is closed.
 */
final class AiSessionActions {
    private static final ArrayDeque<String> APPLY_QUEUE = new ArrayDeque<>();
    private static long lastSent;
    private AiSessionActions() {}

    /** Applies every waiting preview, one after the other. */
    static void applyAll() {
        APPLY_QUEUE.clear();
        for (var view : ClientState.aiSessions) if (view.ghost().total() > 0 && !view.info().stopped()) APPLY_QUEUE.add(view.id());
        if (APPLY_QUEUE.isEmpty()) ClientState.notify("No AI previews are waiting");
    }

    static void discardAll() {
        for (var view : ClientState.aiSessions) if (view.ghost().total() > 0) CraftiumClient.sendAi("ai_cancel", view.id(), null);
    }

    /** Stops every session that is not stopped already. */
    static void stopAll() {
        APPLY_QUEUE.clear();
        for (var view : ClientState.aiSessions) if (!view.info().stopped()) CraftiumClient.sendAi("ai_session_stop", view.id(), null);
    }

    /** Called every client tick. */
    static void tick() {
        if (APPLY_QUEUE.isEmpty()) return;
        long now = System.nanoTime();
        if (now - lastSent < 300_000_000L) return;
        for (var view : ClientState.aiSessions) if (view.info().state().equals("applying")) return;
        String id = APPLY_QUEUE.poll();
        var view = ClientState.aiSession(id);
        if (view != null && view.ghost().total() > 0 && !view.info().stopped()) { CraftiumClient.sendAi("ai_apply", id, null); lastSent = now; }
    }
}
