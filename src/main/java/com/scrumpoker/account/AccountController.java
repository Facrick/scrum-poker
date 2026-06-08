package com.scrumpoker.account;

import com.scrumpoker.model.Deck;
import com.scrumpoker.model.Room;
import com.scrumpoker.service.RoomService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.*;

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
    record RenameSessionRequest(String name) {}
    record UpdateProfileRequest(String displayName) {}

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

    // ── PATCH /api/me — сменить отображаемое имя ─────────────────

    @PatchMapping
    public ResponseEntity<UserResponse> updateMe(
            @AuthenticationPrincipal OAuth2User oauthUser,
            @RequestBody(required = false) UpdateProfileRequest req) {
        if (oauthUser == null) return ResponseEntity.status(401).build();
        String userId = oauthUser.getAttribute("_userId");
        String name = (req != null && req.displayName() != null) ? req.displayName().strip() : "";
        if (name.isEmpty()) return ResponseEntity.badRequest().build();
        if (name.length() > 40) name = name.substring(0, 40);
        userRepository.updateDisplayName(userId, name);
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

    // ── PATCH /api/me/sessions/{roomId} — переименовать ──────────

    @PatchMapping("/sessions/{roomId}")
    public ResponseEntity<Void> renameSession(
            @AuthenticationPrincipal OAuth2User oauthUser,
            @PathVariable String roomId,
            @RequestBody(required = false) RenameSessionRequest req) {
        if (oauthUser == null) return ResponseEntity.status(401).build();
        String userId = oauthUser.getAttribute("_userId");
        String name = (req != null && req.name() != null) ? req.name().strip() : "";
        if (name.isEmpty()) return ResponseEntity.badRequest().build();
        if (name.length() > 80) name = name.substring(0, 80);
        return roomService.renameOwnedSession(roomId, userId, name)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    // ── DELETE /api/me/sessions/{roomId} — завершить и удалить ────

    @DeleteMapping("/sessions/{roomId}")
    public ResponseEntity<Void> deleteSession(
            @AuthenticationPrincipal OAuth2User oauthUser,
            @PathVariable String roomId) {
        if (oauthUser == null) return ResponseEntity.status(401).build();
        String userId = oauthUser.getAttribute("_userId");
        return roomService.deleteOwnedSession(roomId, userId)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    // ── GET /api/me/sessions/{roomId}/report — детальный отчёт ───

    public record ReportVote(String name, String value) {}
    public record ReportItem(String title, String estimate, int revotes, List<ReportVote> votes) {}
    public record SessionReport(String roomName, List<ReportItem> items) {}

    @GetMapping("/sessions/{roomId}/report")
    public ResponseEntity<SessionReport> report(
            @AuthenticationPrincipal OAuth2User oauthUser,
            @PathVariable String roomId) {
        if (oauthUser == null) return ResponseEntity.status(401).build();
        String userId = oauthUser.getAttribute("_userId");
        return roomService.loadOwnedRoom(roomId, userId)
                .map(room -> {
                    List<ReportItem> items = room.getBacklog().stream()
                            .map(i -> new ReportItem(
                                    i.getTitle(),
                                    i.getEstimate(),
                                    i.getRevotes(),
                                    i.getVotes().stream()
                                            .map(v -> new ReportVote(v.name(), v.value()))
                                            .toList()))
                            .toList();
                    return ResponseEntity.ok(new SessionReport(room.getName(), items));
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
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
        List<String> tasks = (req != null) ? req.tasks() : null;
        Room room = roomService.createRoom(name, deck, userId, tasks);
        return ResponseEntity.ok(Map.of("roomId", room.getId()));
    }
}
