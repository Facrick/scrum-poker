package com.scrumpoker.config;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

@Epic("Персистентность")
@Feature("Разбор Railway DATABASE_URL → JDBC")
@DisplayName("RailwayDatabaseEnvironmentPostProcessor")
class RailwayDatabaseEnvironmentPostProcessorTest {

    private final RailwayDatabaseEnvironmentPostProcessor processor =
            new RailwayDatabaseEnvironmentPostProcessor();

    @Test
    @DisplayName("postgresql:// раскладывается на jdbc-url, username, password, driver")
    void convertsRailwayUrlToJdbc() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("DATABASE_URL", "postgresql://alice:s3cret@db.internal:6543/poker");

        processor.postProcessEnvironment(env, null);

        assertThat(env.getProperty("spring.datasource.url"))
                .isEqualTo("jdbc:postgresql://db.internal:6543/poker");
        assertThat(env.getProperty("spring.datasource.username")).isEqualTo("alice");
        assertThat(env.getProperty("spring.datasource.password")).isEqualTo("s3cret");
        assertThat(env.getProperty("spring.datasource.driver-class-name"))
                .isEqualTo("org.postgresql.Driver");
    }

    @Test
    @DisplayName("Без порта используется 5432")
    void defaultsPortWhenMissing() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("DATABASE_URL", "postgres://u:p@host/poker");
        processor.postProcessEnvironment(env, null);
        assertThat(env.getProperty("spring.datasource.url"))
                .isEqualTo("jdbc:postgresql://host:5432/poker");
    }

    @Test
    @DisplayName("Уже-JDBC URL не трогается")
    void leavesJdbcUrlUntouched() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("DATABASE_URL", "jdbc:postgresql://host:5432/poker");
        processor.postProcessEnvironment(env, null);
        // наш источник не добавлен → spring.datasource.url не определён процессором
        assertThat(env.getProperty("spring.datasource.url")).isNull();
    }

    @Test
    @DisplayName("Без DATABASE_URL ничего не делает")
    void noopWhenAbsent() {
        MockEnvironment env = new MockEnvironment();
        processor.postProcessEnvironment(env, null);
        assertThat(env.getProperty("spring.datasource.url")).isNull();
    }
}
