package com.scrumpoker.config;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@Epic("Аутентификация")
@Feature("JwtService: выпуск/проверка/продление токена")
@DisplayName("JwtService")
class JwtServiceTest {

    private final JwtService jwt =
            new JwtService("test-secret-test-secret-test-secret-123", 168);

    @Test
    @DisplayName("issue → parseUserId возвращает subject")
    void issueAndParseRoundtrip() {
        String token = jwt.issue("user-42");
        assertThat(jwt.parseUserId(token)).isEqualTo("user-42");
    }

    @Test
    @DisplayName("parseUserId возвращает null для мусора")
    void parseRejectsGarbage() {
        assertThat(jwt.parseUserId("not-a-jwt")).isNull();
        assertThat(jwt.parseUserId("a.b.c")).isNull();
    }

    @Test
    @DisplayName("issue с пустым userId запрещён")
    void issueRejectsBlankUserId() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jwt.issue(""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Свежий токен (полный срок) не продлевается")
    void freshTokenNotRenewed() {
        String token = jwt.issue("user-42");
        assertThat(jwt.renewIfNeeded(token)).isNull();
    }

    @Test
    @DisplayName("Токен на исходе срока продлевается; мусор — нет")
    void expiringTokenIsRenewed() {
        // TTL = 2 часа → порог продления = 1 час. Выпустим токен сервисом с TTL 1 час
        // и проверим им же с TTL 4 часа: для него «осталось ~1ч» < половины (2ч) → продлить.
        JwtService shortTtl = new JwtService("test-secret-test-secret-test-secret-123", 1);
        JwtService longTtl  = new JwtService("test-secret-test-secret-test-secret-123", 4);
        String nearExpiry = shortTtl.issue("user-42");
        assertThat(longTtl.renewIfNeeded(nearExpiry)).isNotNull();
        assertThat(longTtl.renewIfNeeded("garbage")).isNull();
    }
}
