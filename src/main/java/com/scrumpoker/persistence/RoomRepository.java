package com.scrumpoker.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class RoomRepository {

    private final JdbcTemplate jdbc;

    public RoomRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

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
                jdbc.update(
                        "UPDATE room_snapshots SET snapshot = ?, updated_at = CURRENT_TIMESTAMP WHERE room_id = ?",
                        snapshotJson, roomId);
            }
        }
    }

    /** Load a single room snapshot by room ID. */
    public Optional<String> findById(String roomId) {
        List<String> rows = jdbc.queryForList(
                "SELECT snapshot FROM room_snapshots WHERE room_id = ?", String.class, roomId);
        return rows.stream().findFirst();
    }

    public List<String> findAll() {
        return jdbc.queryForList("SELECT snapshot FROM room_snapshots", String.class);
    }

    public void delete(String roomId) {
        jdbc.update("DELETE FROM room_snapshots WHERE room_id = ?", roomId);
    }

    public void deleteAll(List<String> roomIds) {
        if (roomIds.isEmpty()) return;
        String placeholders = String.join(",", roomIds.stream().map(id -> "?").toList());
        jdbc.update("DELETE FROM room_snapshots WHERE room_id IN (" + placeholders + ")",
                roomIds.toArray());
    }
}
