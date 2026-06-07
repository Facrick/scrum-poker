package com.scrumpoker.account;

import com.scrumpoker.config.JwtService;
import com.scrumpoker.model.Room;
import com.scrumpoker.service.RoomService;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Полный HTTP-путь создания комнаты из ЛК со списком задач:
 * проверяет, что {@code tasks} корректно десериализуется и бэклог наполняется.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Epic("ЛК модератора")
@Feature("POST /api/me/rooms с задачами")
@DisplayName("Создание комнаты из ЛК со списком задач (HTTP+JWT)")
class CreateRoomWithTasksIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired UserRepository userRepository;
    @Autowired JwtService jwtService;
    @Autowired RoomService roomService;

    @Test
    @DisplayName("tasks из JSON наполняют бэклог, первая задача активна")
    void createsRoomWithBacklog() throws Exception {
        User user = userRepository.upsert("github", "gh-tasks-1",
                "alice@example.com", "Alice", null);
        String token = jwtService.issue(user.id());

        MvcResult res = mvc.perform(post("/api/me/rooms")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Спринт\",\"deck\":\"FIBONACCI\"," +
                                 "\"tasks\":[\"Авторизация\",\"Корзина\",\"Оплата\"]}"))
                .andExpect(status().isOk())
                .andReturn();

        String body = res.getResponse().getContentAsString();
        String roomId = body.replaceAll(".*\"roomId\"\\s*:\\s*\"([^\"]+)\".*", "$1");

        Room room = roomService.getRoom(roomId).orElseThrow();
        assertThat(room.getBacklog()).extracting("title")
                .containsExactly("Авторизация", "Корзина", "Оплата");
        assertThat(room.getActiveItemId()).isEqualTo(room.getBacklog().get(0).getId());
        assertThat(room.getOwnerUserId()).isEqualTo(user.id());
    }
}
