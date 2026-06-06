package com.scrumpoker.service;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Простой потокобезопасный лимитер на основе фиксированного окна.
 * Используется для защиты от флуда: создание комнат (по IP) и WS-сообщения (по сессии).
 * Не требует внешних зависимостей; для горизонтального масштабирования
 * заменяется на Redis-бэкенд.
 */
@Component
public class RateLimiter {

    private record Window(long startMillis, AtomicInteger count) {}

    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    /**
     * @return true, если запрос разрешён; false — если лимит превышен.
     * @param key          ключ (IP, sessionId и т.п.)
     * @param maxRequests  максимум запросов за окно
     * @param windowMillis длительность окна в миллисекундах
     */
    public boolean allow(String key, int maxRequests, long windowMillis) {
        long now = System.currentTimeMillis();
        Window w = windows.compute(key, (k, cur) -> {
            if (cur == null || now - cur.startMillis() >= windowMillis) {
                return new Window(now, new AtomicInteger(0));
            }
            return cur;
        });
        return w.count().incrementAndGet() <= maxRequests;
    }

    /** Периодическая очистка устаревших окон, чтобы карта не росла бесконечно. */
    public void evictOlderThan(long windowMillis) {
        long now = System.currentTimeMillis();
        windows.values().removeIf(w -> now - w.startMillis() >= windowMillis);
    }
}
