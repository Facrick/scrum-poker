package com.scrumpoker.account;

import com.scrumpoker.model.Deck;
import com.scrumpoker.model.Room;
import com.scrumpoker.service.RoomService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/me")
public class AccountController {

    private final UserRepository userRepository;
    private final SessionHistoryRepository sessionHistoryRepository;
    private final RoomService roomService;

    public AccountController(UserRepository userRepository,
                             SessionHistoryRepository sessionHistoryRepository,
                             RoomService roomService) {
        this.userRepository = userRepository;
        this.sessionHistoryRepository = sessionHistoryRepository;
        this.roomService = roomService;
    }

    public record UserResponse(
            String id,
            String displayName,
            String email,
            String avatarUrl,
            String provider
    ) {}

    public record SessionResponse(
            String roomId,
            String roomName,
            int participantCount,
            int taskCount,
            int estimatedCount,
            String startedAt,
            String lastActiveAt,
            boolean alive          // комната ещё живёт в памяти сервера
    ) {}

    record CreateRoomRequest(String name, String deck, List<String> tasks) {}

    // ── GET /api/me ───────────────────────────────────────────────

    @GetMapping
    public ResponseEntity<UserResponse> me(@AuthenticationPrincipal OAuth2User oauthUser) {
        if (oauthUser == null) return ResponseEntity.status(401).build();
        String userId = oauthUser.getAttribute("_userId");
        return userRepository.findById(userId)
                .map(u -> ResponseEntity.ok(new UserResponse(
                        u.id(), u.displayName(), u.email(), u.avatarUrl(), u.provider())))
                .orElseGet(() -> ResponseEntity.status(401).build());
    }

    // ── GET /api/me/sessions ──────────────────────────────────────

    @GetMapping("/sessions")
    public List<SessionResponse> sessions(@AuthenticationPrincipal OAuth2User oauthUser) {
        if (oauthUser == null) return List.of();
        String userId = oauthUser.getAttribute("_userId");
        return sessionHistoryRepository.findByOwnerUserId(userId).stream()
                .map(s -> new SessionResponse(
                        s.roomId(), s.roomName(),
                        s.participantCount(), s.taskCount(), s.estimatedCount(),
                        s.startedAt().toString(), s.lastActiveAt().toString(),
                        roomService.getRoom(s.roomId()).isPresent()))
                .toList();
    }

    // ── POST /api/me/rooms — создать комнату из кабинета ─────────

    @PostMapping("/rooms")
    public ResponseEntity<Map<String, String>> createRoom(
            @AuthenticationPrincipal OAuth2User oauthUser,
            @RequestBody(required = false) CreateRoomRequest req) {
        if (oauthUser == null) return ResponseEntity.status(401).build();
        String userId = oauthUser.getAttribute("_userId");
        String name = (req != null && req.name() != null && !req.name().isBlank())
                ? req.name() : "Новая сессия";
        Deck deck = Deck.FIBONACCI;
        if (req != null && req.deck() != null) {
            try { deck = Deck.valueOf(req.deck().toUpperCase()); }
            catch (IllegalArgumentException ignored) { /* оставляем FIBONACCI */ }
        }
        Room room = roomService.createRoom(name, deck, userId);
        // Предзаполнить бэклог задачами из ЛК
        if (req != null && req.tasks() != null) {
            req.tasks().stream()
                .filter(t -> t != null && !t.isBlank())
                .map(String::trim)
                .forEach(title -> room.getBacklog().add(new com.scrumpoker.model.BacklogItem(title)));
        }
        roomService.persistRoom(room);
        return ResponseEntity.ok(Map.of("roomId", room.getId()));
    }
}
