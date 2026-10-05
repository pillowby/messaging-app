package com.example.chat.message;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface MessageRepository extends JpaRepository<Message, Long> {

    Optional<Message> findBySenderIdAndClientMsgId(Long senderId, String clientMsgId);

    /**
     * Stores the message, or returns the already-stored one if this (sender, clientMsgId) was
     * seen before. This makes client retries safe: if the socket dropped after the server
     * persisted but before the ack arrived, the client resends and gets the original message
     * back instead of creating a duplicate.
     *
     * Two tabs or a fast retry can still race past the lookup; the UNIQUE (sender_id,
     * client_msg_id) constraint then rejects the second insert and it re-reads the first.
     * This works because the method itself is not transactional: the failed insert rolls back
     * on its own and the re-read runs in a fresh transaction.
     */
    default Message saveIdempotent(Long senderId, Long recipientId, String body, String clientMsgId) {
        return findBySenderIdAndClientMsgId(senderId, clientMsgId).orElseGet(() -> {
            try {
                return saveAndFlush(new Message(senderId, recipientId, body, clientMsgId));
            } catch (DataIntegrityViolationException e) {
                return findBySenderIdAndClientMsgId(senderId, clientMsgId).orElseThrow();
            }
        });
    }

    /**
     * One page of the conversation between two users, newest first, older than {@code beforeId}.
     * Keyset pagination on id (not OFFSET): new messages keep arriving while the user scrolls
     * back, and with OFFSET those arrivals would shift the pages and produce duplicates/gaps.
     */
    @Query("""
            SELECT m FROM Message m
            WHERE ((m.senderId = :userA AND m.recipientId = :userB)
                OR (m.senderId = :userB AND m.recipientId = :userA))
              AND m.id < :beforeId
            ORDER BY m.id DESC""")
    List<Message> findPage(Long userA, Long userB, Long beforeId, Limit limit);

    /** The latest message of each conversation the user is part of, most recent conversation first. */
    @Query("""
            SELECT m FROM Message m
            WHERE m.id IN (
                SELECT MAX(x.id) FROM Message x
                WHERE x.senderId = :userId OR x.recipientId = :userId
                GROUP BY CASE WHEN x.senderId = :userId THEN x.recipientId ELSE x.senderId END)
            ORDER BY m.id DESC""")
    List<Message> findLatestPerConversation(Long userId);

    /** How many unread messages the user has from each sender. */
    @Query("""
            SELECT m.senderId AS senderId, COUNT(m) AS count FROM Message m
            WHERE m.recipientId = :userId AND m.readAt IS NULL
            GROUP BY m.senderId""")
    List<UnreadCount> countUnreadBySender(Long userId);

    /**
     * Marks everything the reader received from {@code senderId} up to {@code upToId} as read.
     * "Up to an id" rather than a list of ids, so one receipt covers a whole burst of messages
     * and a late/duplicate receipt is harmless ("readAt IS NULL" makes it idempotent).
     *
     * @return how many rows changed; 0 means there is nothing new to tell the sender.
     */
    @Transactional
    @Modifying
    @Query("""
            UPDATE Message m SET m.readAt = :now
            WHERE m.recipientId = :readerId AND m.senderId = :senderId
              AND m.id <= :upToId AND m.readAt IS NULL""")
    int markRead(Long readerId, Long senderId, Long upToId, OffsetDateTime now);

    interface UnreadCount {
        Long getSenderId();

        long getCount();
    }
}
