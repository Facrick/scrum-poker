package com.scrumpoker.account;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.core.user.OAuth2User;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@Epic("ЛК модератора")
@Feature("Кабинет: API-эндпоинты")
@DisplayName("AccountController: /api/me и /api/me/sessions")
class AccountControllerTest {

    private final UserRepository            userRepo    = mock(UserRepository.class);
    private final SessionHistoryRepository  historyRepo = mock(SessionHistoryRepository.class);
    private final AccountController         controller  = new AccountController(userRepo, historyRepo);

    // ─── Вспомогательные фабрики ──────────────────────────────────

    private OAuth2User oauthUser(String userId) {
        OAuth2User u = mock(OAuth2User.class);
        when(u.getAttribute("_userId")).thenReturn(userId);
        return u;
    }

    private User dbUser(String id) {
        return new User(id, "github", "gh-42", "alice@example.com", "Alice", "https://img/a.png");
    }

    private SessionHistory session(String roomId, String userId) {
        Instant now = Instant.now();
        return new SessionHistory(roomId, userId, "Sprint " + roomId, 5, 8, 6, now.minusSeconds(3600), now);
    }

    // ─── GET /api/me ──────────────────────────────────────────────

    @Test
    @Severity(SeverityLevel.BLOCKER)
    @DisplayName("GET /api/me → 401, если нет аутентификации")
    void meReturns401WhenNotAuthenticated() {
        ResponseEntity<AccountController.UserResponse> res = controller.me(null);
        assertThat(res.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    @Severity(SeverityLevel.CRITICAL)
    @DisplayName("GET /api/me → 200 с данными профиля для авторизованного пользователя")
    void meReturnsProfileForAuthenticatedUser() {
        String userId = "user-1";
        when(userRepo.findById(userId)).thenReturn(Optional.of(dbUser(userId)));

        ResponseEntity<AccountController.UserResponse> res = controller.me(oauthUser(userId));

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        AccountController.UserResponse body = res.getBody();
        assertThat(body).isNotNull();
        assertThat(body.displayName()).isEqualTo("Alice");
        assertThat(body.email()).isEqualTo("alice@example.com");
        assertThat(body.provider()).isEqualTo("github");
    }

    @Test
    @DisplayName("GET /api/me → 401, если userId не найден в БД")
    void meReturns401WhenUserNotFoundInDb() {
        when(userRepo.findById("ghost")).thenReturn(Optional.empty());

        ResponseEntity<AccountController.UserResponse> res = controller.me(oauthUser("ghost"));

        assertThat(res.getStatusCode().value()).isEqualTo(401);
    }

    // ─── GET /api/me/sessions ─────────────────────────────────────

    @Test
    @Severity(SeverityLevel.NORMAL)
    @DisplayName("GET /api/me/sessions → пустой список, если нет аутентификации")
    void sessionsReturnsEmptyListWhenNotAuthenticated() {
        List<AccountController.SessionResponse> res = controller.sessions(null);
        assertThat(res).isEmpty();
    }

    @Test
    @Severity(SeverityLevel.CRITICAL)
    @DisplayName("GET /api/me/sessions → список сессий с корректным маппингом полей")
    void sessionsReturnsMappedSessionList() {
        String userId = "user-2";
        List<SessionHistory> history = List.of(
                session("roomA001", userId),
                session("roomB002", userId)
        );
        when(historyRepo.findByOwnerUserId(userId)).thenReturn(history);

        List<AccountController.SessionResponse> res = controller.sessions(oauthUser(userId));

        assertThat(res).hasSize(2);
        assertThat(res.get(0).roomId()).isEqualTo("roomA001");
        assertThat(res.get(0).roomName()).isEqualTo("Sprint roomA001");
        assertThat(res.get(0).participantCount()).isEqualTo(5);
        assertThat(res.get(0).taskCount()).isEqualTo(8);
        assertThat(res.get(0).estimatedCount()).isEqualTo(6);
        assertThat(res.get(0).startedAt()).isNotNull();
        assertThat(res.get(0).lastActiveAt()).isNotNull();
    }

    @Test
    @DisplayName("GET /api/me/sessions → пустой список, если у пользователя нет сессий")
    void sessionsReturnsEmptyListWhenNoSessionsExist() {
        String userId = "user-3";
        when(historyRepo.findByOwnerUserId(userId)).thenReturn(List.of());

        List<AccountController.SessionResponse> res = controller.sessions(oauthUser(userId));

        assertThat(res).isEmpty();
    }
}
