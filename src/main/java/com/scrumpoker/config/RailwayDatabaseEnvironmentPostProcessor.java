package com.scrumpoker.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

/**
 * Railway (и Heroku) отдают строку подключения к Postgres в формате
 * {@code postgresql://user:pass@host:port/db}, который НЕ является JDBC-URL —
 * Spring такой не примет, и приложение молча останется на H2 in-memory,
 * теряя все сессии при каждом рестарте/редеплое.
 * <p>
 * Этот post-processor выполняется до автоконфигурации DataSource: если
 * {@code DATABASE_URL} задан в формате postgres(ql)://, он раскладывается на
 * корректные {@code spring.datasource.url/username/password/driver-class-name}.
 * Если переменной нет — ничего не делаем (локально/тесты остаются на H2).
 */
public class RailwayDatabaseEnvironmentPostProcessor implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication application) {
        String raw = env.getProperty("DATABASE_URL");
        if (raw == null || raw.isBlank()) return;
        if (!raw.startsWith("postgres://") && !raw.startsWith("postgresql://")) {
            return; // уже JDBC-URL или иной формат — не трогаем
        }

        try {
            URI uri = URI.create(raw);
            String userInfo = uri.getUserInfo();           // "user:pass" или null
            String username = null, password = null;
            if (userInfo != null) {
                int colon = userInfo.indexOf(':');
                username = colon >= 0 ? userInfo.substring(0, colon) : userInfo;
                password = colon >= 0 ? userInfo.substring(colon + 1) : "";
            }
            int port = uri.getPort() > 0 ? uri.getPort() : 5432;
            String path = uri.getPath() == null ? "" : uri.getPath();
            String jdbcUrl = "jdbc:postgresql://" + uri.getHost() + ":" + port + path
                    + (uri.getQuery() != null ? "?" + uri.getQuery() : "");

            Map<String, Object> props = new HashMap<>();
            props.put("spring.datasource.url", jdbcUrl);
            props.put("spring.datasource.driver-class-name", "org.postgresql.Driver");
            if (username != null) props.put("spring.datasource.username", username);
            if (password != null) props.put("spring.datasource.password", password);

            // addFirst — выше application.properties, чтобы переопределить ${DATABASE_URL}.
            env.getPropertySources().addFirst(new MapPropertySource("railwayDatabaseUrl", props));
        } catch (Exception e) {
            // Некорректный URL — оставляем как есть, пусть автоконфигурация ругнётся явно.
            System.err.println("Не удалось разобрать DATABASE_URL как postgres URL: " + e.getMessage());
        }
    }
}
