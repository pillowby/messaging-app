package com.example.chat.realtime;

import com.example.chat.message.Message;
import com.example.chat.message.MessageRepository;
import com.example.chat.user.User;
import com.example.chat.user.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.time.OffsetDateTime;

/**
 * The chat protocol. Every frame is a JSON object with a "type":
 *
 * <pre>
 *  client -> server                                 server -> client
 *  {type:"send", to, body, clientMsgId}             {type:"hello", me}
 *  {type:"read", peer, upToId}                      {type:"message", message}
 *  {type:"typing", to}                              {type:"read", readerId, senderId, upToId}
 *  {type:"ping"}                                    {type:"typing", from} / {type:"pong"}
 *                                                   {type:"error", clientMsgId, error}
 * </pre>
 */
@Component
public class ChatSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(ChatSocketHandler.class);
    private static final String DECORATED_ATTR = "decorated";
    private static final int MAX_BODY = 4000;
    private static final int MAX_CLIENT_ID = 64;

    private final SessionRegistry registry;
    private final ChatRouter router;
    private final MessageRepository messages;
    private final UserRepository users;
    private final ObjectMapper json;

    public ChatSocketHandler(SessionRegistry registry, ChatRouter router, MessageRepository messages,
                             UserRepository users, ObjectMapper json) {
        this.registry = registry;
        this.router = router;
        this.messages = messages;
        this.users = users;
        this.json = json;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession raw) {
        User me = userOf(raw);
        // A plain WebSocketSession is NOT safe for concurrent sends: if Alice and Bob both
        // message Carol at the same moment, two threads write to Carol's socket and the
        // container throws "TEXT_PARTIAL_WRITING". The decorator serializes sends through a
        // buffer. The limits also stop one slow client (bad mobile network) from blocking
        // the sender's thread: past 10s or 512KB queued, that session is closed instead,
        // and the client catches up from history when it reconnects.
        WebSocketSession session = new ConcurrentWebSocketSessionDecorator(raw, 10_000, 512 * 1024);
        raw.getAttributes().put(DECORATED_ATTR, session);

        registry.add(me.getId(), session);
        router.send(session, new Events.Hello(me));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession raw, CloseStatus status) {
        registry.remove(userOf(raw).getId(), raw.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession raw, TextMessage frame) {
        User me = userOf(raw);
        WebSocketSession session = (WebSocketSession) raw.getAttributes().get(DECORATED_ATTR);
        JsonNode in;
        try {
            in = json.readTree(frame.getPayload());
        } catch (Exception e) {
            router.send(session, new Events.Error(null, "Malformed JSON"));
            return;
        }
        // Catch everything per frame: an exception escaping here makes Spring close the whole
        // socket, and one bad frame shouldn't disconnect the user.
        try {
            switch (in.path("type").asText()) {
                case "send" -> onSend(me, session, in);
                case "read" -> onRead(me, in);
                case "typing" -> onTyping(me, in);
                case "ping" -> router.send(session, new Events.Pong());
                default -> router.send(session, new Events.Error(null, "Unknown type"));
            }
        } catch (Exception e) {
            log.warn("Failed to handle frame from user {}", me.getId(), e);
            router.send(session, new Events.Error(in.path("clientMsgId").asText(null), "Server error"));
        }
    }

    private void onSend(User me, WebSocketSession session, JsonNode in) {
        String clientMsgId = in.path("clientMsgId").asText("");
        String body = in.path("body").asText("").strip();
        long to = in.path("to").asLong(-1);

        if (clientMsgId.isEmpty() || clientMsgId.length() > MAX_CLIENT_ID) {
            router.send(session, new Events.Error(null, "Missing or invalid clientMsgId"));
            return;
        }
        if (body.isEmpty() || body.length() > MAX_BODY) {
            router.send(session, new Events.Error(clientMsgId, "Message must be 1-" + MAX_BODY + " characters"));
            return;
        }
        if (to == me.getId() || !users.existsById(to)) {
            router.send(session, new Events.Error(clientMsgId, "Unknown recipient"));
            return;
        }

        // Persist BEFORE pushing. If we pushed first and the insert then failed, the recipient
        // would have seen a message that doesn't exist. With this order the worst case is a
        // stored message whose live push failed, which history sync on reconnect recovers.
        Message saved = messages.saveIdempotent(me.getId(), to, body, clientMsgId);

        // On a retried clientMsgId this pushes the original message again. That is
        // deliberate: we can't tell whether the first push reached everyone, and clients
        // de-duplicate by message id, so a repeat is harmless and a gap is not.
        var event = new Events.NewMessage(saved);
        router.sendToUser(saved.getRecipientId(), event);
        router.sendToUser(me.getId(), event);
    }

    private void onRead(User me, JsonNode in) {
        long peer = in.path("peer").asLong(-1);
        long upToId = in.path("upToId").asLong(-1);
        if (peer < 0 || upToId < 0) {
            return;
        }
        if (messages.markRead(me.getId(), peer, upToId, OffsetDateTime.now()) > 0) {
            var receipt = new Events.ReadReceipt(me.getId(), peer, upToId);
            router.sendToUser(peer, receipt);   // sender's ticks turn blue
            router.sendToUser(me.getId(), receipt); // my other tabs clear their unread badge
        }
    }

    private void onTyping(User me, JsonNode in) {
        // Typing indicators are fire-and-forget and never stored: a stale one is worse than a
        // missing one. The client throttles these so we don't relay every keystroke.
        long to = in.path("to").asLong(-1);
        if (to >= 0 && to != me.getId()) {
            router.sendToUser(to, new Events.Typing(me.getId()));
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.debug("Transport error on {}: {}", session.getId(), exception.toString());
    }

    private static User userOf(WebSocketSession session) {
        return (User) session.getAttributes().get(ChatHandshakeInterceptor.USER_ATTR);
    }
}
