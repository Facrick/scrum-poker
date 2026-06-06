package com.scrumpoker.health;

import com.scrumpoker.service.RoomService;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Epic("Наблюдаемость")
@Feature("Health-индикатор персистентности")
@DisplayName("PersistenceHealthIndicator: статус по сбоям записи в БД")
class PersistenceHealthIndicatorTest {

    private final RoomService roomService = mock(RoomService.class);
    private final PersistenceHealthIndicator indicator = new PersistenceHealthIndicator(roomService);

    @Test
    @DisplayName("UP, когда сбоев нет")
    void upWhenNoFailures() {
        when(roomService.getConsecutivePersistFailures()).thenReturn(0);
        Health health = indicator.health();
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("consecutiveFailures", 0);
    }

    @Test
    @DisplayName("DOWN с числом сбоев, когда запись в БД падает")
    void downWhenFailuresPresent() {
        when(roomService.getConsecutivePersistFailures()).thenReturn(3);
        Health health = indicator.health();
        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("consecutiveFailures", 3);
        assertThat(health.getDetails()).containsKey("message");
    }
}
