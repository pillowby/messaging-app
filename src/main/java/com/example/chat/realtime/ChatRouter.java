package com.example.chat.realtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.Collection;

/**
 * Turns "deliver this event to user X" into writes on X's open sockets.
 * Kept separate from the socket handler so the delivery rules are in one place and every
 * kind of event (messages, receipts, typing) is sent the same way.
 */
@Component
public class ChatRouter {

    private static final Logger log = LoggerFactory.getLogger(ChatRouter.class);

    private final SessionRegistry registry;
    private final ObjectMapper json;

    public ChatRouter(SessionRegistry registry, ObjectMapper json) {
        this.registry = registry;
        this.json = json;
    }

    public void sendToUser(long userId, Object event) {
        sendAll(registry.sessionsOf(userId), event);
    }

    public void send(WebSocketSession session, Object event) {
        sendAll(java.util.List.of(session), event);
    }

    private void sendAll(Collection<WebSocketSession> sessions, Object event) {
        if (sessions.isEmpty()) {
            return;
        }
        // Serialize once, not once per session; a user can have many open sockets.
        TextMessage frame;
        try {
            frame = new TextMessage(json.writeValueAsString(event));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unserializable event " + event, e);
        }
        for (WebSocketSession s : sessions) {
            try {
                if (s.isOpen()) {
                    s.sendMessage(frame);
                }
            } catch (IOException | RuntimeException e) {
                // RuntimeException too: the decorator signals a stuck slow client with
                // SessionLimitExceededException, which is unchecked.
                // One broken socket must not stop delivery to the user's other tabs/devices.
                // A failed live push loses nothing: the message is already in the DB, and the
                // client reloads history when it reconnects.
                log.debug("Dropping session {} after send failure: {}", s.getId(), e.toString());
                closeQuietly(s);
            }
        }
    }

    private static void closeQuietly(WebSocketSession s) {
        try {
            s.close(CloseStatus.SESSION_NOT_RELIABLE);
        } catch (IOException ignored) {
            // Already dead; afterConnectionClosed will clean up the registry.
        }
    }
}
