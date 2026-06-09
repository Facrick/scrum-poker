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
 * Доступна без авторизации по id комнаты — отдаёт только агрегат
 * (задачи + оценки + число переголосований), без имён голосовавших.
 */
@RestController
@RequestMapping("/api/public")
public class PublicController {

    private final RoomService roomService;

    public PublicController(RoomService roomService) {
        this.roomService = roomService;
    }

    public record PublicVote(String name, String value) {}
    public record PublicItem(String title, String estimate, int revotes,
                             boolean consensus, List<PublicVote> votes) {}
    public record PublicSummary(String roomName, int taskCount, int estimatedCount,
                                List<PublicItem> items) {}

    @GetMapping("/sessions/{roomId}")
    public ResponseEntity<PublicSummary> summary(@PathVariable String roomId) {
        return roomService.loadAnyRoom(roomId)
                .map(room -> {
                    List<BacklogItem> backlog = room.getBacklog();
                    List<PublicItem> items = backlog.stream()
                            .map(i -> {
                                List<PublicVote> votes = i.getVotes().stream()
                                        .map(v -> new PublicVote(v.name(), v.value()))
                                        .toList();
                                boolean consensus = !votes.isEmpty()
                                        && votes.stream().map(PublicVote::value).distinct().count() == 1;
                                return new PublicItem(i.getTitle(), i.getEstimate(),
                                        i.getRevotes(), consensus, votes);
                            })
                            .toList();
                    int estimated = (int) backlog.stream().filter(i -> i.getEstimate() != null).count();
                    return ResponseEntity.ok(new PublicSummary(
                            room.getName(), backlog.size(), estimated, items));
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
