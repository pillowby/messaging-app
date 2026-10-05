package com.example.chat.realtime;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks which WebSocket sessions belong to which user.
 *
 * A user maps to *many* sessions, not one: the same account can be open in several tabs or
 * on several devices at once, and every one of them must receive each message.
 *
 * This is in-memory, so it only knows about sockets connected to this JVM. That is fine for a
 * single app container; running several replicas would need a cross-node fan-out (e.g.
 * Postgres LISTEN/NOTIFY or Redis pub/sub) in front of {@link ChatRouter#sendToUser}.
 */
@Component
public class SessionRegistry {

    private final Map<Long, Map<String, WebSocketSession>> byUser = new ConcurrentHashMap<>();

    /**
     * The put happens inside compute(), not after computeIfAbsent(): otherwise a disconnect
     * racing this connect could drop the user's map in between and the new session would be lost.
     */
    public void add(long userId, WebSocketSession session) {
        byUser.compute(userId, (id, sessions) -> {
            if (sessions == null) {
                sessions = new ConcurrentHashMap<>();
            }
            sessions.put(session.getId(), session);
            return sessions;
        });
    }

    public void remove(long userId, String sessionId) {
        // Returning null drops the map entry so idle users don't leak memory.
        byUser.computeIfPresent(userId, (id, sessions) -> {
            sessions.remove(sessionId);
            return sessions.isEmpty() ? null : sessions;
        });
    }

    public Collection<WebSocketSession> sessionsOf(long userId) {
        Map<String, WebSocketSession> sessions = byUser.get(userId);
        return sessions == null ? List.of() : List.copyOf(sessions.values());
    }
}
