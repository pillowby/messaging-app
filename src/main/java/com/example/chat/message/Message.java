package com.example.chat.message;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;

/**
 * Sender and recipient are plain ids rather than @ManyToOne relations: the client only needs
 * the ids, and it keeps the JSON shape flat with nothing lazily loaded during serialization.
 */
@Entity
@Table(name = "messages")
public class Message {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long senderId;
    private Long recipientId;
    private String body;
    private String clientMsgId;

    @CreationTimestamp
    private OffsetDateTime createdAt;

    private OffsetDateTime readAt;

    protected Message() {
        // for JPA
    }

    public Message(Long senderId, Long recipientId, String body, String clientMsgId) {
        this.senderId = senderId;
        this.recipientId = recipientId;
        this.body = body;
        this.clientMsgId = clientMsgId;
    }

    public Long getId() {
        return id;
    }

    public Long getSenderId() {
        return senderId;
    }

    public Long getRecipientId() {
        return recipientId;
    }

    public String getBody() {
        return body;
    }

    public String getClientMsgId() {
        return clientMsgId;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getReadAt() {
        return readAt;
    }
}
