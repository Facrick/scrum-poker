package com.scrumpoker.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class RoomRepository {

    private final JdbcTemplate jdbc;

    public RoomRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Сохранить/обновить снимок комнаты (upsert).
     * Реализовано через UPDATE + INSERT вместо MERGE/ON CONFLICT, чтобы работать
     * одинаково на H2 (локально/тесты) и PostgreSQL (прод). Предыдущая версия
     * использовала H2-специфичный `MERGE ... KEY`, который падал на PostgreSQL.
     */
    public void save(String roomId, String snapshotJson) {
        int updated = jdbc.update(
                "UPDATE room_snapshots SET snapshot = ?, updated_at = CURRENT_TIMESTAMP WHERE room_id = ?",
                snapshotJson, roomId);
        if (updated == 0) {
            try {
                jdbc.update(
                        "INSERT INTO room_snapshots (room_id, snapshot, updated_at) VALUES (?, ?, CURRENT_TIMESTAMP)",
                        roomId, snapshotJson);
            } catch (org.springframework.dao.DuplicateKeyException race) {
                // Конкурентная вставка тем же room_id успела первой — повторяем UPDATE.
                jdbc.update(
                        "UPDATE room_snapshots SET snapshot = ?, updated_at = CURRENT_TIMESTAMP WHERE room_id = ?",
                        snapshotJson, roomId);
            }
        }
    }

    /** Загрузить все снимки (при старте сервера). */
    public List<String> findAll() {
        return jdbc.queryForList("SELECT snapshot FROM room_snapshots", String.class);
    }

    /** Удалить снимок комнаты (при удалении комнаты). */
    public void delete(String roomId) {
        jdbc.update("DELETE FROM room_snapshots WHERE room_id = ?", roomId);
    }

    /** Удалить снимки комнат из списка (при TTL-очистке). */
    public void deleteAll(List<String> roomIds) {
        if (roomIds.isEmpty()) return;
        String placeholders = String.join(",", roomIds.stream().map(id -> "?").toList());
        jdbc.update("DELETE FROM room_snapshots WHERE room_id IN (" + placeholders + ")",
                roomIds.toArray());
    }
}
