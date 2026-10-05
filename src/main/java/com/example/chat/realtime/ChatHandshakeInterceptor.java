package com.example.chat.realtime;

import com.example.chat.user.CurrentUserInterceptor;
import com.example.chat.user.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;

/**
 * Identifies the user on the WebSocket upgrade request before the connection is accepted.
 *
 * The user id comes from the query string because the browser WebSocket API has no way to set
 * custom headers. There is no authentication: the id is trusted as-is.
 */
@Component
public class ChatHandshakeInterceptor implements HandshakeInterceptor {

    static final String USER_ATTR = "user";

    private final UserRepository users;

    public ChatHandshakeInterceptor(UserRepository users) {
        this.users = users;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String userId = UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams().getFirst("userId");
        var user = CurrentUserInterceptor.parseId(userId).flatMap(users::findById);
        if (user.isEmpty()) {
            response.setStatusCode(HttpStatus.BAD_REQUEST);
            return false;
        }
        // Resolved once here and pinned to the session, so we don't hit the DB on every frame.
        attributes.put(USER_ATTR, user.get());
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
    }
}
