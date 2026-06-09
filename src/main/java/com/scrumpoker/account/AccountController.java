package com.scrumpoker.account;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scrumpoker.model.BacklogItem;
import com.scrumpoker.model.Deck;
import com.scrumpoker.model.Room;
import com.scrumpoker.persistence.RoomRepository;
import com.scrumpoker.persistence.RoomSnapshot;
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
    private final RoomRepository roomRepository;
    private final ObjectMapper objectMapper;

    public AccountController(UserRepository userRepository,
                             SessionHistoryRepository sessionHistoryRepository,
                             RoomService roomService,
                             RoomRepository roomRepository,
                             ObjectMapper objectMapper) {
        this.userRepository = userRepository;
        this.sessionHistoryRepository = sessionHistoryRepository;
        this.roomService = roomService;
        this.roomRepository = roomRepository;
        this.objectMapper = objectMapper;
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
            boolean alive
    ) {}

    record CreateRoomRequest(String name, String deck, List<String> tasks) {}
    record UpdateProfileRequest(String displayName) {}
    record RenameSessionRequest(String name) {}

    // GET /api/me
    @GetMapping
    public ResponseEntity<UserResponse> me(@AuthenticationPrincipal OAuth2User oauthUser) {
        if (oauthUser == null) return ResponseEntity.status(401).build();
        String userId = oauthUser.getAttribute("_userId");
        if (userId == null) return ResponseEntity.status(401).build();
        return userRepository.findById(userId)
                .map(u -> ResponseEntity.ok(new UserResponse(
                        u.id(), u.displayName(), u.email(), u.avatarUrl(), u.provider())))
                .orElseGet(() -> ResponseEntity.status(401).build());
    }

    // PATCH /api/me
    @PatchMapping
    public ResponseEntity<UserResponse> updateProfile(@AuthenticationPrincipal OAuth2User oauthUser,
                                                       @RequestBody UpdateProfileRequest req) {
        if (oauthUser == null) return ResponseEntity.status(401).build();
        String userId = oauthUser.getAttribute("_userId");
        if (userId == null) return ResponseEntity.status(401).build();
        String name = req.displayName() == null ? "" : req.displayName().trim();
        if (name.isEmpty() || name.length() > 100) return ResponseEntity.badRequest().build();
        userRepository.updateDisplayName(userId, name);
        return userRepository.findById(userId)
                .map(u -> ResponseEntity.ok(new UserResponse(
                        u.id(), u.displayName(), u.email(), u.avatarUrl(), u.provider())))
                .orElseGet(() -> ResponseEntity.status(401).build());
    }

    // GET /api/me/sessions
    @GetMapping("/sessions")
    public List<SessionResponse> sessions(@AuthenticationPrincipal OAuth2User oauthUser) {
        if (oauthUser == null) return List.of();
        String userId = oauthUser.getAttribute("_userId");
        if (userId == null) return List.of();
        return sessionHistoryRepository.findByOwnerUserId(userId).stream()
                .map(s -> new SessionResponse(
                        s.roomId(), s.roomName(),
                        s.participantCount(), s.taskCount(), s.estimatedCount(),
                        s.startedAt().toString(), s.lastActiveAt().toString(),
                        roomService.getRoom(s.roomId()).isPresent()))
                .toList();
    }

    // PATCH /api/me/sessions/{roomId}
    @PatchMapping("/sessions/{roomId}")
    public ResponseEntity<Void> renameSession(@AuthenticationPrincipal OAuth2User oauthUser,
                                               @PathVariable String roomId,
                                               @RequestBody RenameSessionRequest req) {
        if (oauthUser == null) return ResponseEntity.status(401).build();
        String userId = oauthUser.getAttribute("_userId");
        if (userId == null) return ResponseEntity.status(401).build();
        String name = req.name() == null ? "" : req.name().trim();
        if (name.isEmpty() || name.length() > 60) return ResponseEntity.badRequest().build();
        sessionHistoryRepository.rename(roomId, userId, name);
        roomService.getRoom(roomId).ifPresent(r -> {
            if (userId.equals(r.getOwnerUserId())) {
                r.setName(name);
                roomService.persistRoom(r);
            }
        });
        return ResponseEntity.ok().build();
    }

    // DELETE /api/me/sessions/{roomId}
    @DeleteMapping("/sessions/{roomId}")
    public ResponseEntity<Void> deleteSession(@AuthenticationPrincipal OAuth2User oauthUser,
                                               @PathVariable String roomId) {
        if (oauthUser == null) return ResponseEntity.status(401).build();
        String userId = oauthUser.getAttribute("_userId");
        if (userId == null) return ResponseEntity.status(401).build();
        roomService.getRoom(roomId).ifPresent(r -> {
            if (userId.equals(r.getOwnerUserId())) roomService.removeEmptyRoom(r);
        });
        sessionHistoryRepository.delete(roomId, userId);
        return ResponseEntity.ok().build();
    }

    // GET /api/me/sessions/{roomId}/report
    @GetMapping("/sessions/{roomId}/report")
    public ResponseEntity<?> sessionReport(@AuthenticationPrincipal OAuth2User oauthUser,
                                            @PathVariable String roomId) {
        if (oauthUser == null) return ResponseEntity.status(401).build();
        String userId = oauthUser.getAttribute("_userId");
        if (userId == null) return ResponseEntity.status(401).build();

        Optional<Room> liveRoom = roomService.getRoom(roomId);
        if (liveRoom.isPresent()) {
            return ResponseEntity.ok(buildReportData(liveRoom.get()));
        }

        Optional<String> snapshotJson = roomRepository.findById(roomId);
        if (snapshotJson.isPresent()) {
            try {
                RoomSnapshot snap = objectMapper.readValue(snapshotJson.get(), RoomSnapshot.class);
                return ResponseEntity.ok(buildReportData(snap.toRoom()));
            } catch (Exception e) {
                return ResponseEntity.status(500).build();
            }
        }

        return ResponseEntity.notFound().build();
    }

    private Map<String, Object> buildReportData(Room room) {
        List<Map<String, Object>> items = room.getBacklog().stream()
                .map(i -> {
                    Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("title", i.getTitle());
                    m.put("estimate", i.getEstimate() != null ? i.getEstimate() : "");
                    m.put("revotes", i.getRevotes());
                    m.put("votes", i.getVotes().stream()
                            .map(v -> Map.of("name", v.name(), "value", v.value()))
                            .toList());
                    return m;
                })
                .toList();
        return Map.of("roomName", room.getName(), "items", items);
    }

    // POST /api/me/rooms
    @PostMapping("/rooms")
    public ResponseEntity<Map<String, String>> createRoom(
            @AuthenticationPrincipal OAuth2User oauthUser,
            @RequestBody(required = false) CreateRoomRequest req) {
        if (oauthUser == null) return ResponseEntity.status(401).build();
        String userId = oauthUser.getAttribute("_userId");
        if (userId == null) return ResponseEntity.status(401).build();
        String name = (req != null && req.name() != null && !req.name().isBlank())
                ? req.name() : "Новая сессия";
        Deck deck = Deck.FIBONACCI;
        if (req != null && req.deck() != null) {
            try { deck = Deck.valueOf(req.deck().toUpperCase()); }
            catch (IllegalArgumentException ignored) {}
        }
        Room room = roomService.createRoom(name, deck, userId);
        if (req != null && req.tasks() != null) {
            req.tasks().stream()
                .filter(t -> t != null && !t.isBlank())
                .map(String::trim)
                .forEach(title -> room.getBacklog().add(new BacklogItem(title)));
        }
        // Немедленная запись при наличии задач — чтобы не потерять их при рестарте сервера.
        if (!room.getBacklog().isEmpty()) roomService.persistRoomNow(room);
        else roomService.persistRoom(room);
        return ResponseEntity.ok(Map.of("roomId", room.getId()));
    }
}
