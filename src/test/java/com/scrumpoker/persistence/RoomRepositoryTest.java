package com.scrumpoker.persistence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Проверяет, что upsert работает на реальной БД (H2 в режиме PostgreSQL).
 * Это страховка от регресса бага P0 с MERGE-синтаксисом.
 */
@JdbcTest
@Import(RoomRepository.class)
class RoomRepositoryTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RoomRepository repository;

    @BeforeEach
    void schema() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS room_snapshots (
                    room_id    VARCHAR(8)  NOT NULL PRIMARY KEY,
                    snapshot   TEXT        NOT NULL,
                    updated_at TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
                )""");
        jdbc.update("DELETE FROM room_snapshots");
    }

    @Test
    void insertsNewRow() {
        repository.save("room1", "{\"v\":1}");
        List<String> all = repository.findAll();
        assertThat(all).containsExactly("{\"v\":1}");
    }

    @Test
    void upsertUpdatesExistingRowWithoutDuplicating() {
        repository.save("room1", "{\"v\":1}");
        repository.save("room1", "{\"v\":2}");   // тот же room_id — должен обновить, не вставить второй
        List<String> all = repository.findAll();
        assertThat(all).containsExactly("{\"v\":2}");
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM room_snapshots WHERE room_id = ?", Integer.class, "room1");
        assertThat(count).isEqualTo(1);
    }

    @Test
    void deleteRemovesRow() {
        repository.save("room1", "{}");
        repository.delete("room1");
        assertThat(repository.findAll()).isEmpty();
    }

    @Test
    void deleteAllRemovesListedRooms() {
        repository.save("a", "{}");
        repository.save("b", "{}");
        repository.save("c", "{}");
        repository.deleteAll(List.of("a", "c"));
        assertThat(repository.findAll()).containsExactly("{}");   // остался только b
    }
}
