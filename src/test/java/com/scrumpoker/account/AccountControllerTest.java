package com.scrumpoker.account;

import com.scrumpoker.model.Room;
import com.scrumpoker.model.Deck;
import com.scrumpoker.service.RoomService;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.core.user.OAuth2User;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@Epic("ЛК модератора")
@Feature("Кабинет: API-эндпоинты")
@DisplayName("AccountController: /api/me, /api/me/sessions, /api/me/rooms")
class AccountControllerTest {

    private final UserRepository           userRepo    = mock(UserRepository.class);
    private final SessionHistoryRepository historyRepo = mock(SessionHistoryRepository.class);
    private final RoomService              roomService = mock(RoomService.class);
    private final AccountController        controller  =
            new AccountController(userRepo, historyRepo, roomService);

    @BeforeEach
    void setUp() {
        // По умолчанию все комнаты не существуют (alive = false)
        when(roomService.getRoom(anyString())).thenReturn(Optional.empty());
    }

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
        assertThat(res.get(0).alive()).isFalse(); // комната не в памяти
    }

    @Test
    @DisplayName("GET /api/me/sessions → alive=true для живой комнаты")
    void sessionsMarksAliveRoomCorrectly() {
        String userId = "user-4";
        when(historyRepo.findByOwnerUserId(userId)).thenReturn(List.of(session("liveRoom", userId)));
        Room liveRoom = new Room("liveRoom", "Live", Deck.FIBONACCI);
        when(roomService.getRoom("liveRoom")).thenReturn(Optional.of(liveRoom));

        List<AccountController.SessionResponse> res = controller.sessions(oauthUser(userId));

        assertThat(res).hasSize(1);
        assertThat(res.get(0).alive()).isTrue();
    }

    @Test
    @DisplayName("GET /api/me/sessions → пустой список, если у пользователя нет сессий")
    void sessionsReturnsEmptyListWhenNoSessionsExist() {
        String userId = "user-3";
        when(historyRepo.findByOwnerUserId(userId)).thenReturn(List.of());

        List<AccountController.SessionResponse> res = controller.sessions(oauthUser(userId));

        assertThat(res).isEmpty();
    }

    // ─── PATCH /api/me/sessions/{id} ──────────────────────────────

    @Test
    @Severity(SeverityLevel.BLOCKER)
    @DisplayName("PATCH /sessions → 401 без аутентификации")
    void renameReturns401WhenNotAuthenticated() {
        ResponseEntity<Void> res = controller.renameSession(null, "r1",
                new AccountController.RenameSessionRequest("New"));
        assertThat(res.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    @DisplayName("PATCH /sessions → 400 при пустом имени")
    void renameReturns400OnEmptyName() {
        ResponseEntity<Void> res = controller.renameSession(oauthUser("u"), "r1",
                new AccountController.RenameSessionRequest("   "));
        assertThat(res.getStatusCode().value()).isEqualTo(400);
        verify(roomService, never()).renameOwnedSession(anyString(), anyString(), anyString());
    }

    @Test
    @Severity(SeverityLevel.CRITICAL)
    @DisplayName("PATCH /sessions → 204 и делегирует переименование владельцу")
    void renameDelegatesToServiceScopedByOwner() {
        when(roomService.renameOwnedSession("r1", "u", "New name")).thenReturn(true);
        ResponseEntity<Void> res = controller.renameSession(oauthUser("u"), "r1",
                new AccountController.RenameSessionRequest("  New name  "));
        assertThat(res.getStatusCode().value()).isEqualTo(204);
        verify(roomService).renameOwnedSession("r1", "u", "New name");
    }

    @Test
    @DisplayName("PATCH /sessions → 404, если сессия не принадлежит пользователю")
    void renameReturns404WhenNotOwned() {
        when(roomService.renameOwnedSession("r1", "u", "X")).thenReturn(false);
        ResponseEntity<Void> res = controller.renameSession(oauthUser("u"), "r1",
                new AccountController.RenameSessionRequest("X"));
        assertThat(res.getStatusCode().value()).isEqualTo(404);
    }

    // ─── DELETE /api/me/sessions/{id} ─────────────────────────────

    @Test
    @Severity(SeverityLevel.BLOCKER)
    @DisplayName("DELETE /sessions → 401 без аутентификации")
    void deleteReturns401WhenNotAuthenticated() {
        ResponseEntity<Void> res = controller.deleteSession(null, "r1");
        assertThat(res.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    @Severity(SeverityLevel.CRITICAL)
    @DisplayName("DELETE /sessions → 204 и делегирует удаление владельцу")
    void deleteDelegatesToServiceScopedByOwner() {
        when(roomService.deleteOwnedSession("r1", "u")).thenReturn(true);
        ResponseEntity<Void> res = controller.deleteSession(oauthUser("u"), "r1");
        assertThat(res.getStatusCode().value()).isEqualTo(204);
        verify(roomService).deleteOwnedSession("r1", "u");
    }

    @Test
    @DisplayName("DELETE /sessions → 404, если удалять нечего/не его")
    void deleteReturns404WhenNothingDeleted() {
        when(roomService.deleteOwnedSession("r1", "u")).thenReturn(false);
        ResponseEntity<Void> res = controller.deleteSession(oauthUser("u"), "r1");
        assertThat(res.getStatusCode().value()).isEqualTo(404);
    }

    // ─── GET /api/me/sessions/{id}/report ─────────────────────────

    @Test
    @Severity(SeverityLevel.BLOCKER)
    @DisplayName("GET /report → 401 без аутентификации")
    void reportReturns401WhenNotAuthenticated() {
        ResponseEntity<AccountController.SessionReport> res = controller.report(null, "r1");
        assertThat(res.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    @Severity(SeverityLevel.CRITICAL)
    @DisplayName("GET /report → детальный отчёт владельца (задачи, оценки, голоса)")
    void reportReturnsDetailForOwner() {
        Room room = new Room("r1", "Спринт 7", Deck.FIBONACCI);
        com.scrumpoker.model.BacklogItem item = new com.scrumpoker.model.BacklogItem("Логин");
        item.setEstimate("5");
        item.setRevotes(1);
        item.setVotes(List.of(new com.scrumpoker.model.BacklogItem.RoundVote("Alice", "5")));
        room.getBacklog().add(item);
        when(roomService.loadOwnedRoom("r1", "u")).thenReturn(Optional.of(room));

        ResponseEntity<AccountController.SessionReport> res = controller.report(oauthUser("u"), "r1");

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        AccountController.SessionReport body = res.getBody();
        assertThat(body).isNotNull();
        assertThat(body.roomName()).isEqualTo("Спринт 7");
        assertThat(body.items()).hasSize(1);
        assertThat(body.items().get(0).estimate()).isEqualTo("5");
        assertThat(body.items().get(0).revotes()).isEqualTo(1);
        assertThat(body.items().get(0).votes()).extracting("name").containsExactly("Alice");
    }

    @Test
    @DisplayName("GET /report → 404, если сессия не найдена/не его")
    void reportReturns404WhenNotOwned() {
        when(roomService.loadOwnedRoom("r1", "u")).thenReturn(Optional.empty());
        ResponseEntity<AccountController.SessionReport> res = controller.report(oauthUser("u"), "r1");
        assertThat(res.getStatusCode().value()).isEqualTo(404);
    }

    // ─── POST /api/me/rooms ───────────────────────────────────────

    @Test
    @Severity(SeverityLevel.BLOCKER)
    @DisplayName("POST /api/me/rooms → 401 без аутентификации")
    void createRoomReturns401WhenNotAuthenticated() {
        ResponseEntity<Map<String, String>> res = controller.createRoom(null, null);
        assertThat(res.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    @Severity(SeverityLevel.CRITICAL)
    @DisplayName("POST /api/me/rooms → 200 с roomId, комната привязана к аккаунту")
    void createRoomLinksToAccount() {
        String userId = "user-5";
        Room room = new Room("abc12345", "Спринт 1", Deck.FIBONACCI);
        when(roomService.createRoom("Спринт 1", Deck.FIBONACCI, userId, null)).thenReturn(room);

        ResponseEntity<Map<String, String>> res = controller.createRoom(
                oauthUser(userId),
                new AccountController.CreateRoomRequest("Спринт 1", "FIBONACCI", null));

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody()).containsEntry("roomId", "abc12345");
        verify(roomService).createRoom("Спринт 1", Deck.FIBONACCI, userId, null);
    }
}
