package com.example.chat.realtime;

import com.example.chat.message.Message;
import com.example.chat.user.User;

/**
 * Server -> client events. Every event carries a "type" string so the browser can dispatch
 * on it; records give us a fixed, documented shape for each one.
 *
 * Client -> server frames are parsed loosely in {@link ChatSocketHandler} instead, because
 * they come from an untrusted source and need field-by-field validation anyway.
 */
final class Events {

    private Events() {
    }

    /** First frame after connecting: who you are. Tells the client the socket is ready. */
    record Hello(String type, User me) {
        Hello(User me) {
            this("hello", me);
        }
    }

    /**
     * A message that has been persisted. Sent to both sides: the recipient sees it as
     * incoming, and all of the sender's sessions see it too (the sending tab treats it as
     * the ack for its clientMsgId, the other tabs show it as a message they sent).
     */
    record NewMessage(String type, Message message) {
        NewMessage(Message message) {
            this("message", message);
        }
    }

    record ReadReceipt(String type, long readerId, long senderId, long upToId) {
        ReadReceipt(long readerId, long senderId, long upToId) {
            this("read", readerId, senderId, upToId);
        }
    }

    record Typing(String type, long from) {
        Typing(long from) {
            this("typing", from);
        }
    }

    record Pong(String type) {
        Pong() {
            this("pong");
        }
    }

    record Error(String type, String clientMsgId, String error) {
        Error(String clientMsgId, String error) {
            this("error", clientMsgId, error);
        }
    }
}
