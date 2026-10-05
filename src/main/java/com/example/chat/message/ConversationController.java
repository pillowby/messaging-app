package com.example.chat.message;

import com.example.chat.user.CurrentUserInterceptor;
import com.example.chat.user.User;
import com.example.chat.user.UserRepository;
import org.springframework.data.domain.Limit;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Read side over plain HTTP. Live delivery goes over the WebSocket, but history stays on REST
 * because after a reconnect the client needs to catch up on whatever it missed while offline,
 * and a request/response call is the simplest reliable way to do that.
 */
@RestController
@RequestMapping("/api/conversations")
public class ConversationController {

    private final MessageRepository messages;
    private final UserRepository users;

    public ConversationController(MessageRepository messages, UserRepository users) {
        this.messages = messages;
        this.users = users;
    }

    /** One row per chat partner: the latest message plus how many of theirs I haven't read. */
    @GetMapping
    public List<ConversationSummary> list(@RequestAttribute(CurrentUserInterceptor.USER_ATTR) User me) {
        List<Message> latest = messages.findLatestPerConversation(me.getId());
        Map<Long, Long> unread = messages.countUnreadBySender(me.getId()).stream()
                .collect(Collectors.toMap(MessageRepository.UnreadCount::getSenderId,
                        MessageRepository.UnreadCount::getCount));
        Map<Long, User> partners = users.findAllById(latest.stream().map(m -> partnerOf(m, me)).toList())
                .stream().collect(Collectors.toMap(User::getId, Function.identity()));

        return latest.stream().map(m -> {
            Long partnerId = partnerOf(m, me);
            return new ConversationSummary(partnerId, partners.get(partnerId).getUsername(), m,
                    unread.getOrDefault(partnerId, 0L));
        }).toList();
    }

    @GetMapping("/{partnerId}/messages")
    public List<Message> history(@RequestAttribute(CurrentUserInterceptor.USER_ATTR) User me,
                                 @PathVariable long partnerId,
                                 @RequestParam(defaultValue = "" + Long.MAX_VALUE) long before,
                                 @RequestParam(defaultValue = "50") int limit) {
        List<Message> page = new ArrayList<>(
                messages.findPage(me.getId(), partnerId, before, Limit.of(Math.max(1, Math.min(limit, 100)))));
        // The query returns newest first (so the limit keeps the newest); the client renders oldest first.
        Collections.reverse(page);
        return page;
    }

    private static Long partnerOf(Message m, User me) {
        return m.getSenderId().equals(me.getId()) ? m.getRecipientId() : m.getSenderId();
    }

    public record ConversationSummary(long partnerId, String partnerName, Message lastMessage, long unread) {
    }
}
