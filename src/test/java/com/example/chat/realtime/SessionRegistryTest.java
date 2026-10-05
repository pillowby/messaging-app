package com.example.chat.realtime;

import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SessionRegistryTest {

    private final SessionRegistry registry = new SessionRegistry();

    @Test
    void tracksEverySessionOfAUser() {
        var tab1 = session("a");
        var tab2 = session("b");

        registry.add(1, tab1);
        registry.add(1, tab2);
        assertThat(registry.sessionsOf(1)).containsExactlyInAnyOrder(tab1, tab2);

        registry.remove(1, "a");
        assertThat(registry.sessionsOf(1)).containsExactly(tab2);
        registry.remove(1, "b");
        assertThat(registry.sessionsOf(1)).isEmpty();
    }

    @Test
    void removingUnknownSessionIsHarmless() {
        var tab = session("a");
        registry.add(1, tab);
        registry.remove(1, "nope");
        registry.remove(2, "a");
        assertThat(registry.sessionsOf(1)).containsExactly(tab);
        assertThat(registry.sessionsOf(2)).isEmpty();
    }

    /** Many tabs connecting at once must all be registered. */
    @Test
    void concurrentConnectsAreAllRegistered() throws Exception {
        int n = 64;
        List<WebSocketSession> sessions = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            sessions.add(session("s" + i));
        }
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(16);
        for (WebSocketSession s : sessions) {
            pool.submit(() -> {
                start.await();
                registry.add(7, s);
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

        assertThat(registry.sessionsOf(7)).hasSize(n);
    }

    private static WebSocketSession session(String id) {
        WebSocketSession s = mock(WebSocketSession.class);
        when(s.getId()).thenReturn(id);
        return s;
    }
}
