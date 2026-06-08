package com.scrumpoker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scrumpoker.account.SessionHistoryRepository;
import com.scrumpoker.model.Deck;
import com.scrumpoker.model.Participant;
import com.scrumpoker.model.Room;
import com.scrumpoker.persistence.RoomRepository;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

@Epic("Сервисы")
@Feature("Управление комнатами")
@DisplayName("RoomService: участники, лимиты, TTL")
class RoomServiceTest {

    private RoomService service;
    private RoomRepository roomRepo;
    private SessionHistoryRepository historyRepo;

    @BeforeEach
    void setUp() {
        roomRepo = mock(RoomRepository.class);
        historyRepo = mock(SessionHistoryRepository.class);
        service = new RoomService(new ObjectMapper(), roomRepo, historyRepo, new RateLimiter());
        ReflectionTestUtils.setField(service, "roomTtlHours", 8);
    }

    @Test
    @DisplayName("История ЛК не переписывается, если значимые поля не изменились")
    void sessionHistoryWriteDedupedWhenUnchanged() {
        Room room = service.createRoom("S", Deck.FIBONACCI, "owner-1");
        service.persistRoom(room);
        service.persistRoom(room); // то же состояние — лишней записи быть не должно
        org.mockito.Mockito.verify(historyRepo, org.mockito.Mockito.times(1))
                .upsert(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("TTL-очистка хранит снимок владельческой сессии, анонимный — удаляет")
    void evictionKeepsOwnedSessionResults() {
        Room owned = service.createRoom("Owned", Deck.FIBONACCI, "owner-1");
        Room anon  = service.createRoom("Anon", Deck.FIBONACCI);
        java.time.Instant stale = java.time.Instant.now().minus(9, java.time.temporal.ChronoUnit.HOURS);
        ReflectionTestUtils.setField(owned, "lastActivityAt", stale);
        ReflectionTestUtils.setField(anon, "lastActivityAt", stale);

        service.evictStaleRooms();

        // Обе выгружены из памяти…
        assertThat(service.getRoom(owned.getId())).isEmpty();
        assertThat(service.getRoom(anon.getId())).isEmpty();
        // …но снимок удалён только у анонимной комнаты.
        org.mockito.Mockito.verify(roomRepo).deleteAll(
                org.mockito.ArgumentMatchers.argThat(ids ->
                        ids.contains(anon.getId()) && !ids.contains(owned.getId())));
    }

    @Test
    @DisplayName("Создание с задачами наполняет бэклог, первая — активна")
    void createRoomWithTasksPrefillsBacklog() {
        Room room = service.createRoom("Спринт", Deck.FIBONACCI, "owner-1",
                java.util.List.of("Логин", "  ", "Корзина", ""));
        assertThat(room.getBacklog()).hasSize(2);
        assertThat(room.getBacklog().get(0).getTitle()).isEqualTo("Логин");
        assertThat(room.getActiveItemId()).isEqualTo(room.getBacklog().get(0).getId());
        assertThat(room.getCurrentStory()).isEqualTo("Логин");
        assertThat(room.getOwnerUserId()).isEqualTo("owner-1");
    }

    @Test
    @DisplayName("Первый вошедший становится модератором")
    void firstJoinerBecomesModerator() {
        Room room = service.createRoom("Sprint", Deck.FIBONACCI);
        Participant first = service.join(room, "Alice", Participant.Role.PLAYER);
        assertThat(first.getRole()).isEqualTo(Participant.Role.MODERATOR);
    }

    @Test
    @DisplayName("Второй участник сохраняет запрошенную роль")
    void secondJoinerKeepsRequestedRole() {
        Room room = service.createRoom("Sprint", Deck.FIBONACCI);
        service.join(room, "Alice", Participant.Role.PLAYER);            // модератор
        Participant bob = service.join(room, "Bob", Participant.Role.PLAYER);
        assertThat(bob.getRole()).isEqualTo(Participant.Role.PLAYER);
    }

    @Test
    @DisplayName("Поздний участник не становится ведущим, даже если ведущий офлайн")
    void laterJoinerIsNotAutoPromotedWhenAlone() {
        Room room = service.createRoom("Sprint", Deck.FIBONACCI);
        Participant mod = service.join(room, "Alice", Participant.Role.PLAYER); // создатель = ведущий
        mod.setOnline(false);                                                   // ведущий ушёл офлайн
        Participant bob = service.join(room, "Bob", Participant.Role.PLAYER);
        assertThat(bob.getRole()).isEqualTo(Participant.Role.PLAYER);           // авто-передачи прав нет
    }

    @Test
    @DisplayName("Вход отклоняется при заполненной комнате")
    void joinIsRejectedWhenRoomIsFull() {
        Room room = service.createRoom("Sprint", Deck.FIBONACCI);
        for (int i = 0; i < 100; i++) {
            service.join(room, "P" + i, Participant.Role.PLAYER);
        }
        assertThatThrownBy(() -> service.join(room, "overflow", Participant.Role.PLAYER))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("Недавно активная комната не удаляется по TTL")
    void evictionKeepsRecentlyActiveRoom() {
        Room room = service.createRoom("Fresh", Deck.FIBONACCI);
        service.evictStaleRooms();
        assertThat(service.getRoom(room.getId())).isPresent();
    }

    @Test
    @DisplayName("Простаивающая дольше TTL комната удаляется")
    void evictionRemovesRoomIdleLongerThanTtl() {
        Room room = service.createRoom("Stale", Deck.FIBONACCI);
        // Сдвигаем последнюю активность далеко в прошлое (имитация простоя > TTL).
        ReflectionTestUtils.setField(room, "lastActivityAt",
                java.time.Instant.now().minus(9, java.time.temporal.ChronoUnit.HOURS));
        service.evictStaleRooms();
        assertThat(service.getRoom(room.getId())).isEmpty();
    }

    @Test
    @DisplayName("Счётчик сбоев персистентности стартует с нуля")
    void persistFailureCounterStartsAtZero() {
        assertThat(service.getConsecutivePersistFailures()).isZero();
    }
}
