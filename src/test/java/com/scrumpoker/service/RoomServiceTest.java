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

    @BeforeEach
    void setUp() {
        service = new RoomService(new ObjectMapper(), mock(RoomRepository.class),
                mock(SessionHistoryRepository.class), new RateLimiter());
        ReflectionTestUtils.setField(service, "roomTtlHours", 8);
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
