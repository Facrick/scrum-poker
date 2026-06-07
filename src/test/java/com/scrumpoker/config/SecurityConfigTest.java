package com.scrumpoker.config;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Интеграционные тесты правил Spring Security.
 * Проверяют, что анонимный покер-поток не заблокирован,
 * а защищённые эндпоинты ЛК требуют аутентификации.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Epic("Безопасность")
@Feature("Правила авторизации (SecurityConfig)")
@DisplayName("SecurityConfig: защита ЛК и публичный доступ к игровому потоку")
class SecurityConfigTest {

    @Autowired
    private MockMvc mvc;

    // ─── Защищённые маршруты ──────────────────────────────────────

    @Test
    @Severity(SeverityLevel.BLOCKER)
    @DisplayName("GET /account доступен как статика → 200 (защита через JWT на клиенте)")
    void accountPageIsPublicSpaGuardedClientSide() throws Exception {
        // Stateless-аутентификация: /account — это SPA-страница, которая сама
        // проверяет JWT из localStorage и редиректит на /login при его отсутствии.
        // Серверная защита тут невозможна — навигация браузера не несёт Bearer-заголовок.
        // Реально защищены данные: /api/me и /api/me/** (см. тесты ниже).
        mvc.perform(get("/account"))
                .andExpect(status().isOk());
    }

    @Test
    @Severity(SeverityLevel.BLOCKER)
    @DisplayName("GET /api/me без входа → 401 (не редирект)")
    void apiMeRequiresAuthentication() throws Exception {
        mvc.perform(get("/api/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @Severity(SeverityLevel.BLOCKER)
    @DisplayName("GET /api/me/sessions без входа → 401")
    void apiSessionsRequiresAuthentication() throws Exception {
        mvc.perform(get("/api/me/sessions"))
                .andExpect(status().isUnauthorized());
    }

    // ─── Публичные маршруты (анонимный поток не должен ломаться) ─

    @Test
    @Severity(SeverityLevel.BLOCKER)
    @DisplayName("GET / (лобби) доступен без входа → 200")
    void lobbyIsPublic() throws Exception {
        mvc.perform(get("/"))
                .andExpect(status().isOk());
    }

    @Test
    @Severity(SeverityLevel.BLOCKER)
    @DisplayName("POST /api/rooms анонимом → 200 (анонимный вход не сломан)")
    void createRoomIsPublicForAnonymousUser() throws Exception {
        mvc.perform(post("/api/rooms")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Security Test\",\"deck\":\"FIBONACCI\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roomId").exists());
    }

    @Test
    @Severity(SeverityLevel.NORMAL)
    @DisplayName("GET /api/public/sessions/{id} доступен без входа (404, а не 401)")
    void publicSummaryIsAccessibleWithoutAuth() throws Exception {
        mvc.perform(get("/api/public/sessions/nonexistent"))
                .andExpect(status().isNotFound());
    }

    @Test
    @Severity(SeverityLevel.NORMAL)
    @DisplayName("GET /login (страница входа) доступна без аутентификации → 200")
    void loginPageIsPublic() throws Exception {
        mvc.perform(get("/login"))
                .andExpect(status().isOk());
    }
}
