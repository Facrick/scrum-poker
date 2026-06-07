package com.scrumpoker.account;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@JdbcTest
@Import(SessionHistoryRepository.class)
@Epic("ЛК модератора")
@Feature("История сессий: хранилище")
@Severity(SeverityLevel.CRITICAL)
@DisplayName("SessionHistoryRepository: upsert и выборка на реальной БД")
class SessionHistoryRepositoryTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private SessionHistoryRepository repository;

    @BeforeEach
    void schema() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS session_history (
                    room_id           VARCHAR(8)  NOT NULL PRIMARY KEY,
                    owner_user_id     VARCHAR(36) NOT NULL,
                    room_name         VARCHAR(255),
                    participant_count INT         NOT NULL DEFAULT 0,
                    task_count        INT         NOT NULL DEFAULT 0,
                    estimated_count   INT         NOT NULL DEFAULT 0,
                    started_at        TIMESTAMP   NOT NULL,
                    last_active_at    TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
                )""");
        jdbc.update("DELETE FROM session_history");
    }

    private SessionHistory make(String roomId, String userId, Instant lastActive) {
        return new SessionHistory(
                roomId, userId, "Sprint " + roomId,
                5, 3, 2,
                lastActive.minusSeconds(3600), lastActive);
    }

    @Test
    @DisplayName("Вставка новой записи в пустую таблицу")
    void insertsNewEntry() {
        SessionHistory s = make("room0001", "user-a", Instant.now());
        repository.upsert(s);

        List<SessionHistory> found = repository.findByOwnerUserId("user-a");
        assertThat(found).hasSize(1);
        assertThat(found.get(0).roomId()).isEqualTo("room0001");
        assertThat(found.get(0).roomName()).isEqualTo("Sprint room0001");
        assertThat(found.get(0).participantCount()).isEqualTo(5);
    }

    @Test
    @DisplayName("Повторный upsert обновляет запись, не дублируя")
    void upsertUpdatesExistingEntry() {
        SessionHistory first = make("room0002", "user-b", Instant.now().minusSeconds(100));
        repository.upsert(first);

        SessionHistory updated = new SessionHistory(
                "room0002", "user-b", "Обновлённый спринт",
                10, 8, 7,
                first.startedAt(), Instant.now());
        repository.upsert(updated);

        List<SessionHistory> found = repository.findByOwnerUserId("user-b");
        assertThat(found).hasSize(1);
        assertThat(found.get(0).roomName()).isEqualTo("Обновлённый спринт");
        assertThat(found.get(0).participantCount()).isEqualTo(10);

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM session_history WHERE room_id = ?", Integer.class, "room0002");
        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("findByOwnerUserId возвращает записи по убыванию lastActiveAt")
    void findReturnsEntriesOrderedByLastActiveAtDesc() {
        Instant base = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        repository.upsert(make("room0003", "user-c", base.minusSeconds(300)));
        repository.upsert(make("room0004", "user-c", base));
        repository.upsert(make("room0005", "user-c", base.minusSeconds(600)));

        List<SessionHistory> found = repository.findByOwnerUserId("user-c");

        assertThat(found).hasSize(3);
        // Ожидаем порядок: room0004 (самый свежий), room0003, room0005 (самый старый)
        assertThat(found.get(0).roomId()).isEqualTo("room0004");
        assertThat(found.get(2).roomId()).isEqualTo("room0005");
    }

    @Test
    @DisplayName("findByOwnerUserId не возвращает сессии других пользователей")
    void findExcludesOtherUsersEntries() {
        repository.upsert(make("room0006", "user-d", Instant.now()));
        repository.upsert(make("room0007", "user-e", Instant.now()));

        List<SessionHistory> forD = repository.findByOwnerUserId("user-d");
        List<SessionHistory> forE = repository.findByOwnerUserId("user-e");

        assertThat(forD).hasSize(1);
        assertThat(forD.get(0).roomId()).isEqualTo("room0006");
        assertThat(forE).hasSize(1);
        assertThat(forE.get(0).roomId()).isEqualTo("room0007");
    }
}
