package com.scrumpoker.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scrumpoker.model.Deck;
import com.scrumpoker.model.Participant;
import com.scrumpoker.model.Room;
import com.scrumpoker.persistence.RoomRepository;
import com.scrumpoker.service.RateLimiter;
import com.scrumpoker.service.RoomService;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@Epic("WebSocket")
@Feature("Контроллер покера")
@DisplayName("Контроллер покера: авторизация, голоса, реконнект")
class PokerControllerTest {

    private RoomService roomService;
    private PokerController controller;
    private Room room;

    @BeforeEach
    void setUp() {
        roomService = new RoomService(new ObjectMapper(), mock(RoomRepository.class), new RateLimiter());
        controller = new PokerController(roomService, mock(SimpMessagingTemplate.class), new RateLimiter());
        room = roomService.createRoom("Sprint", Deck.FIBONACCI);
    }

    private SimpMessageHeaderAccessor session(String id) {
        SimpMessageHeaderAccessor h = SimpMessageHeaderAccessor.create();
        h.setSessionId(id);
        return h;
    }

    private String join(String name, String role, String existingId, String sessionId) {
        Map<String, String> reply = controller.join(
                room.getId(), new Messages.JoinMessage(name, role, existingId), session(sessionId));
        return reply.get("participantId");
    }

    // ---- Авторизация (P0) ----

    @Test
    @Severity(SeverityLevel.CRITICAL)
    @DisplayName("Модератор может вскрыть карты")
    void moderatorCanReveal() {
        String alice = join("Alice", "PLAYER", null, "s1");   // первый = модератор
        controller.reveal(room.getId(), new Messages.ModeratorAction(alice), session("s1"));
        assertThat(room.isRevealed()).isTrue();
    }

    @Test
    @Severity(SeverityLevel.BLOCKER)
    @DisplayName("Игрок не может вскрыть карты")
    void playerCannotReveal() {
        join("Alice", "PLAYER", null, "s1");                  // модератор
        join("Bob", "PLAYER", null, "s2");                    // игрок
        controller.reveal(room.getId(), new Messages.ModeratorAction("любой-id"), session("s2"));
        assertThat(room.isRevealed()).isFalse();
    }

    @Test
    @Severity(SeverityLevel.BLOCKER)
    @DisplayName("Игрок не может выдать себя за модератора через payload")
    void playerCannotImpersonateModeratorViaPayload() {
        String alice = join("Alice", "PLAYER", null, "s1");   // модератор
        join("Bob", "PLAYER", null, "s2");                    // игрок
        // Боб знает ID модератора и подставляет его в payload — но действует со своей сессии.
        controller.reveal(room.getId(), new Messages.ModeratorAction(alice), session("s2"));
        assertThat(room.isRevealed()).as("payload-id игнорируется, авторизация по сессии").isFalse();
    }

    @Test
    @DisplayName("Голос привязывается к участнику сессии, а не к payload")
    void voteUsesSessionParticipantNotPayload() {
        join("Alice", "PLAYER", null, "s1");
        String bob = join("Bob", "PLAYER", null, "s2");
        // Голос с сессии Боба, но в payload — чужой id. Голос должен записаться Бобу.
        controller.vote(room.getId(), new Messages.VoteMessage("посторонний-id", "5"), session("s2"));
        assertThat(room.getParticipant(bob).getVote()).isEqualTo("5");
    }

    @Test
    @DisplayName("Голос вне текущей колоды отклоняется")
    void voteRejectsValueOutsideDeck() {
        String alice = join("Alice", "PLAYER", null, "s1");
        controller.vote(room.getId(), new Messages.VoteMessage(alice, "999"), session("s1"));
        assertThat(room.getParticipant(alice).getVote()).isNull();
    }

    // ---- Гонка реконнекта (P1) ----

    @Test
    @Severity(SeverityLevel.CRITICAL)
    @DisplayName("Запоздавший disconnect не гасит переподключившегося участника")
    void staleDisconnectDoesNotKnockOutReconnectedParticipant() {
        String alice = join("Alice", "PLAYER", null, "s1");        // сессия s1
        join("Alice", "PLAYER", alice, "s2");                      // реконнект на s2
        assertThat(room.getParticipant(alice).isOnline()).isTrue();

        // Запоздавший disconnect СТАРОЙ сессии s1 не должен гасить участника.
        controller.handleDisconnect("s1");
        assertThat(room.getParticipant(alice).isOnline())
                .as("реконнект на s2 сохраняется").isTrue();

        // Disconnect актуальной сессии s2 — гасит.
        controller.handleDisconnect("s2");
        assertThat(room.getParticipant(alice).isOnline()).isFalse();
    }

    // ---- Конкурентный join (P0/P3) ----

    @Test
    @Severity(SeverityLevel.CRITICAL)
    @DisplayName("Параллельные входы с уникальными именами все успешны")
    void concurrentJoinsWithUniqueNamesAllSucceed() throws InterruptedException {
        int n = 30;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(n);
        for (int i = 0; i < n; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    join("User" + idx, "PLAYER", null, "sess" + idx);
                } catch (InterruptedException ignored) {
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        assertThat(room.size()).isEqualTo(n);
        List<String> ids = room.getParticipants().stream().map(Participant::getId).toList();
        assertThat(ids).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("Модератор может исключить участника")
    void kickRemovesParticipant() {
        String alice = join("Alice", "PLAYER", null, "s1");   // модератор
        String bob = join("Bob", "PLAYER", null, "s2");
        controller.kick(room.getId(), new Messages.KickMessage(alice, bob), session("s1"));
        assertThat(room.getParticipant(bob)).isNull();
    }
}
