package com.scrumpoker.controller;

import com.scrumpoker.model.BacklogItem;
import com.scrumpoker.model.Deck;
import com.scrumpoker.model.Room;
import com.scrumpoker.service.RoomService;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Epic("Публичные итоги")
@Feature("PublicController: /api/public/sessions/{id}")
@DisplayName("Публичная сводка итогов сессии")
class PublicControllerTest {

    private final RoomService roomService = mock(RoomService.class);
    private final PublicController controller = new PublicController(roomService);

    @Test
    @DisplayName("404, если комната не найдена ни в памяти, ни в снимке")
    void returns404WhenRoomMissing() {
        when(roomService.loadAnyRoom("nope")).thenReturn(Optional.empty());
        ResponseEntity<PublicController.PublicSummary> res = controller.summary("nope");
        assertThat(res.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    @DisplayName("Возвращает агрегат: задачи, оценки, число оценённых")
    void returnsAggregateSummary() {
        Room room = new Room("r1", "Спринт 7", Deck.FIBONACCI);
        BacklogItem done = new BacklogItem("Логин");
        done.setEstimate("5");
        done.setRevotes(2);
        room.getBacklog().add(done);
        room.getBacklog().add(new BacklogItem("Корзина")); // без оценки
        when(roomService.loadAnyRoom("r1")).thenReturn(Optional.of(room));

        ResponseEntity<PublicController.PublicSummary> res = controller.summary("r1");

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        PublicController.PublicSummary body = res.getBody();
        assertThat(body).isNotNull();
        assertThat(body.roomName()).isEqualTo("Спринт 7");
        assertThat(body.taskCount()).isEqualTo(2);
        assertThat(body.estimatedCount()).isEqualTo(1);
        assertThat(body.items()).hasSize(2);
        assertThat(body.items().get(0).estimate()).isEqualTo("5");
        assertThat(body.items().get(0).revotes()).isEqualTo(2);
        assertThat(body.items().get(1).estimate()).isNull();
    }
}
