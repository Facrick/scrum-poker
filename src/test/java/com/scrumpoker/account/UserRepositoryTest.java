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

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@JdbcTest
@Import(UserRepository.class)
@Epic("ЛК модератора")
@Feature("Хранилище пользователей")
@Severity(SeverityLevel.BLOCKER)
@DisplayName("UserRepository: upsert и поиск по id")
class UserRepositoryTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private UserRepository repository;

    @BeforeEach
    void schema() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS users (
                    id           VARCHAR(36)   NOT NULL PRIMARY KEY,
                    provider     VARCHAR(20)   NOT NULL,
                    provider_id  VARCHAR(255)  NOT NULL,
                    email        VARCHAR(255),
                    display_name VARCHAR(255),
                    avatar_url   VARCHAR(1024),
                    created_at   TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    CONSTRAINT uq_users_provider UNIQUE (provider, provider_id)
                )""");
        jdbc.update("DELETE FROM users");
    }

    @Test
    @DisplayName("Первый вход создаёт нового пользователя")
    void upsertCreatesNewUser() {
        User user = repository.upsert("google", "g-sub-1", "alice@gmail.com", "Alice", "https://pic/a");

        assertThat(user.id()).isNotNull();
        assertThat(user.provider()).isEqualTo("google");
        assertThat(user.email()).isEqualTo("alice@gmail.com");
        assertThat(user.displayName()).isEqualTo("Alice");
    }

    @Test
    @DisplayName("Повторный вход обновляет данные без дублирования записи")
    void upsertUpdatesExistingUserWithoutDuplicate() {
        User first = repository.upsert("github", "gh-42", "old@example.com", "Bob Old", null);
        User updated = repository.upsert("github", "gh-42", "new@example.com", "Bob New", "https://avatar/b");

        // Тот же id, обновлённые поля
        assertThat(updated.id()).isEqualTo(first.id());
        assertThat(updated.email()).isEqualTo("new@example.com");
        assertThat(updated.displayName()).isEqualTo("Bob New");

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE provider = ? AND provider_id = ?",
                Integer.class, "github", "gh-42");
        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("findById возвращает пользователя по uuid")
    void findByIdReturnsUser() {
        User created = repository.upsert("google", "sub-99", "carol@gmail.com", "Carol", null);

        Optional<User> found = repository.findById(created.id());

        assertThat(found).isPresent();
        assertThat(found.get().email()).isEqualTo("carol@gmail.com");
    }

    @Test
    @DisplayName("findById возвращает empty для несуществующего id")
    void findByIdReturnsEmptyForUnknownId() {
        Optional<User> found = repository.findById("non-existent-uuid");
        assertThat(found).isEmpty();
    }
}
