package com.scrumpoker.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    /**
     * Разрешённые origin для WebSocket (ALLOWED_ORIGIN, можно список через запятую).
     * По умолчанию «*» — WS не передаёт куки/сессии (аутентификация через JWT в теле),
     * поэтому открытый origin безопасен и избавляет от 403 на хостингах вроде Railway,
     * где забыли выставить точный домен. Для жёсткой привязки задайте ALLOWED_ORIGIN.
     */
    @Value("${app.allowed-origin:*}")
    private String allowedOrigin;

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // Простой in-memory брокер. На этап масштабирования заменяется на Redis/RabbitMQ.
        registry.enableSimpleBroker("/topic", "/queue");
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // Нативный WebSocket — без SockJS-обёртки.
        // SockJS использует устаревшие unload-события (Chrome warning),
        // а все актуальные браузеры поддерживают WS напрямую.
        String[] patterns = java.util.Arrays.stream(allowedOrigin.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns(patterns);
    }
}
