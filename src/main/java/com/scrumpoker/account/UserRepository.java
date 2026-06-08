package com.scrumpoker.account;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public class UserRepository {

    private final JdbcTemplate jdbc;

    public UserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Обновляет или создаёт пользователя по паре (provider, providerId).
     * Паттерн UPDATE→INSERT→retry-UPDATE, как в RoomRepository, для защиты от гонок.
     */
    public User upsert(String provider, String providerId,
                       String email, String displayName, String avatarUrl) {
        // ВАЖНО: display_name при повторных входах НЕ перезаписываем — иначе
        // пользовательское имя, заданное в ЛК, сбрасывалось бы на имя из OAuth.
        // Имя из провайдера используется только при первом INSERT.
        int updated = jdbc.update(
                "UPDATE users SET email = ?, avatar_url = ? " +
                "WHERE provider = ? AND provider_id = ?",
                email, avatarUrl, provider, providerId);

        if (updated > 0) {
            return findByProviderKey(provider, providerId).orElseThrow();
        }

        String id = UUID.randomUUID().toString();
        try {
            jdbc.update(
                    "INSERT INTO users (id, provider, provider_id, email, display_name, avatar_url) " +
                    "VALUES (?, ?, ?, ?, ?, ?)",
                    id, provider, providerId, email, displayName, avatarUrl);
        } catch (DuplicateKeyException race) {
            // Параллельный вход создал запись раньше — обновляем без display_name
            jdbc.update(
                    "UPDATE users SET email = ?, avatar_url = ? " +
                    "WHERE provider = ? AND provider_id = ?",
                    email, avatarUrl, provider, providerId);
            return findByProviderKey(provider, providerId).orElseThrow();
        }
        return new User(id, provider, providerId, email, displayName, avatarUrl);
    }

    /** Пользователь меняет отображаемое имя в ЛК. Возвращает число обновлённых строк. */
    public int updateDisplayName(String id, String displayName) {
        return jdbc.update("UPDATE users SET display_name = ? WHERE id = ?", displayName, id);
    }

    public Optional<User> findById(String id) {
        return jdbc.query(
                "SELECT id, provider, provider_id, email, display_name, avatar_url FROM users WHERE id = ?",
                (rs, n) -> new User(
                        rs.getString("id"),
                        rs.getString("provider"),
                        rs.getString("provider_id"),
                        rs.getString("email"),
                        rs.getString("display_name"),
                        rs.getString("avatar_url")),
                id).stream().findFirst();
    }

    private Optional<User> findByProviderKey(String provider, String providerId) {
        return jdbc.query(
                "SELECT id, provider, provider_id, email, display_name, avatar_url " +
                "FROM users WHERE provider = ? AND provider_id = ?",
                (rs, n) -> new User(
                        rs.getString("id"),
                        rs.getString("provider"),
                        rs.getString("provider_id"),
                        rs.getString("email"),
                        rs.getString("display_name"),
                        rs.getString("avatar_url")),
                provider, providerId).stream().findFirst();
    }
}
