package com.example.chat.user;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final CurrentUserInterceptor currentUserInterceptor;

    public WebConfig(CurrentUserInterceptor currentUserInterceptor) {
        this.currentUserInterceptor = currentUserInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // The WebSocket endpoint is not under /api: browsers can't attach custom headers to a
        // WebSocket handshake, so it identifies the user separately (see ChatHandshakeInterceptor).
        registry.addInterceptor(currentUserInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/users/join");
    }
}
