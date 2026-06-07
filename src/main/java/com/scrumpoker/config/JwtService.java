package com.scrumpoker.config;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * Выпуск и проверка JWT для ЛК модераторов.
 * <p>
 * Stateless-подход выбран намеренно: Railway завершает TLS снаружи и
 * нестабильно пробрасывает Set-Cookie, а контейнер перезапускается/масштабируется,
 * из-за чего in-memory HTTP-сессии теряются. Токен в localStorage + заголовок
 * {@code Authorization: Bearer} полностью убирают зависимость от кук и сессий.
 * <p>
 * В токене хранится только {@code sub = userId}; профиль всегда подтягивается
 * из {@code UserRepository} по этому id, поэтому компрометация токена не раскрывает
 * лишних данных и не требует ревокации (короткий TTL).
 */
@Service
public class JwtService {

    private final SecretKey key;
    private final long ttlMillis;

    public JwtService(
            @Value("${app.jwt.secret:}") String secret,
            @Value("${app.jwt.ttl-hours:168}") long ttlHours) {
        // Без заданного секрета (локальная разработка) используем стабильный dev-ключ.
        // В production обязателен JWT_SECRET длиной ≥ 32 байт.
        String effective = (secret == null || secret.isBlank())
                ? "dev-only-insecure-secret-change-me-via-JWT_SECRET-env-32b"
                : secret;
        byte[] bytes = effective.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException(
                    "JWT_SECRET должен быть не короче 32 байт (получено " + bytes.length + ")");
        }
        this.key = Keys.hmacShaKeyFor(bytes);
        this.ttlMillis = ttlHours * 60 * 60 * 1000;
    }

    /** Выпускает подписанный токен для пользователя. */
    public String issue(String userId) {
        if (userId == null || userId.isBlank()) {
            // Защита от регресса: если принципал не содержит _userId (например,
            // провайдер пошёл по OIDC-ветке мимо OAuthUserService), лучше упасть
            // явно, чем выдать токен без subject и получить молчаливый 401 на /api/me.
            throw new IllegalArgumentException("Не могу выпустить JWT: userId пуст");
        }
        Date now = new Date();
        return Jwts.builder()
                .subject(userId)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + ttlMillis))
                .signWith(key)
                .compact();
    }

    /**
     * Проверяет подпись/срок и возвращает userId (subject),
     * либо {@code null} если токен невалиден или просрочен.
     */
    public String parseUserId(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload()
                    .getSubject();
        } catch (JwtException | IllegalArgumentException e) {
            return null;
        }
    }
}
