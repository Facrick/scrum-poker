package com.scrumpoker.account;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;

@Repository
public class SessionHistoryRepository {

    private final JdbcTemplate jdbc;

    public SessionHistoryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void upsert(SessionHistory s) {
        int updated = jdbc.update(
                "UPDATE session_history SET room_name = ?, participant_count = ?, task_count = ?, " +
                "estimated_count = ?, last_active_at = ? WHERE room_id = ?",
                s.roomName(), s.participantCount(), s.taskCount(),
                s.estimatedCount(), Timestamp.from(s.lastActiveAt()), s.roomId());

        if (updated == 0) {
            try {
                jdbc.update(
                        "INSERT INTO session_history (room_id, owner_user_id, room_name, participant_count, " +
                        "task_count, estimated_count, started_at, last_active_at) VALUES (?,?,?,?,?,?,?,?)",
                        s.roomId(), s.ownerUserId(), s.roomName(), s.participantCount(),
                        s.taskCount(), s.estimatedCount(),
                        Timestamp.from(s.startedAt()), Timestamp.from(s.lastActiveAt()));
            } catch (DuplicateKeyException race) {
                jdbc.update(
                        "UPDATE session_history SET room_name = ?, participant_count = ?, task_count = ?, " +
                        "estimated_count = ?, last_active_at = ? WHERE room_id = ?",
                        s.roomName(), s.participantCount(), s.taskCount(),
                        s.estimatedCount(), Timestamp.from(s.lastActiveAt()), s.roomId());
            }
        }
    }

    /** Rename a session (owner-only). */
    public void rename(String roomId, String ownerUserId, String newName) {
        jdbc.update(
                "UPDATE session_history SET room_name = ? WHERE room_id = ? AND owner_user_id = ?",
                newName, roomId, ownerUserId);
    }

    /** Delete a session from history (owner-only). */
    public void delete(String roomId, String ownerUserId) {
        jdbc.update(
                "DELETE FROM session_history WHERE room_id = ? AND owner_user_id = ?",
                roomId, ownerUserId);
    }

    public List<SessionHistory> findByOwnerUserId(String userId) {
        return jdbc.query(
                "SELECT room_id, owner_user_id, room_name, participant_count, task_count, " +
                "estimated_count, started_at, last_active_at FROM session_history " +
                "WHERE owner_user_id = ? ORDER BY last_active_at DESC",
                (rs, n) -> new SessionHistory(
                        rs.getString("room_id"),
                        rs.getString("owner_user_id"),
                        rs.getString("room_name"),
                        rs.getInt("participant_count"),
                        rs.getInt("task_count"),
                        rs.getInt("estimated_count"),
                        rs.getTimestamp("started_at").toInstant(),
                        rs.getTimestamp("last_active_at").toInstant()),
                userId);
    }
}
