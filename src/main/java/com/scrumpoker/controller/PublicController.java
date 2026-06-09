package com.scrumpoker.controller;

import com.scrumpoker.model.BacklogItem;
import com.scrumpoker.service.RoomService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Публичная read-only сводка итогов сессии (шаринг-ссылка).
 * Доступна без авторизации по id комнаты.
 */
@RestController
@RequestMapping("/api/public")
public class PublicController {

    private final RoomService roomService;

    public PublicController(RoomService roomService) {
        this.roomService = roomService;
    }

    public record PublicItem(String title, String estimate) {}
    public record PublicSummary(String roomName, int taskCount, int estimatedCount,
                                List<PublicItem> items) {}

    @GetMapping("/sessions/{roomId}")
    public ResponseEntity<PublicSummary> summary(@PathVariable String roomId) {
        return roomService.getRoom(roomId)
                .map(room -> {
                    List<BacklogItem> backlog = room.getBacklog();
                    List<PublicItem> items = backlog.stream()
                            .map(i -> new PublicItem(i.getTitle(), i.getEstimate()))
                            .toList();
                    int estimated = (int) backlog.stream()
                            .filter(i -> i.getEstimate() != null).count();
                    return ResponseEntity.ok(new PublicSummary(
                            room.getName(), backlog.size(), estimated, items));
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
