package com.example.chat.user;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.util.Optional;

/**
 * Resolves the caller for /api/** from the X-User-Id header. There is no authentication:
 * the id is trusted as-is, so any client can act as any user.
 */
@Component
public class CurrentUserInterceptor implements HandlerInterceptor {

    public static final String USER_ATTR = "currentUser";

    private final UserRepository users;

    public CurrentUserInterceptor(UserRepository users) {
        this.users = users;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        var user = parseId(request.getHeader("X-User-Id")).flatMap(users::findById);
        if (user.isEmpty()) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Missing or unknown X-User-Id");
            return false;
        }
        request.setAttribute(USER_ATTR, user.get());
        return true;
    }

    public static Optional<Long> parseId(String value) {
        try {
            return value == null ? Optional.empty() : Optional.of(Long.parseLong(value.trim()));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
