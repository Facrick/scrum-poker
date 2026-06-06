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

    /** Сохранить/обновить снимок комнаты (upsert). */
    public void save(String roomId, String snapshotJson) {
        jdbc.update("""
                MERGE INTO room_snapshots (room_id, snapshot, updated_at)
                KEY (room_id)
                VALUES (?, ?, CURRENT_TIMESTAMP)
                """, roomId, snapshotJson);
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
