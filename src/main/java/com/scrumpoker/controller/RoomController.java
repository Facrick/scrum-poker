package com.scrumpoker.controller;

import com.scrumpoker.model.Deck;
import com.scrumpoker.model.Room;
import com.scrumpoker.service.RateLimiter;
import com.scrumpoker.service.RoomService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/rooms")
public class RoomController {

    // Не более 20 создаваемых комнат с одного IP за минуту.
    private static final int CREATE_LIMIT = 20;
    private static final long CREATE_WINDOW_MS = 60_000;

    private final RoomService roomService;
    private final RateLimiter rateLimiter;

    public RoomController(RoomService roomService, RateLimiter rateLimiter) {
        this.roomService = roomService;
        this.rateLimiter = rateLimiter;
    }

    public record CreateRoomRequest(@Size(max = 80) String name, String deck) {}
    public record RoomResponse(String roomId, String roomName, String deck) {}

    @PostMapping
    public RoomResponse create(@RequestBody(required = false) CreateRoomRequest req,
                               HttpServletRequest http,
                               @AuthenticationPrincipal OAuth2User oauthUser) {
        String ip = clientIp(http);
        if (!rateLimiter.allow("create:" + ip, CREATE_LIMIT, CREATE_WINDOW_MS)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Слишком много комнат создано. Попробуйте через минуту.");
        }
        Deck deck = Deck.FIBONACCI;
        String name = null;
        if (req != null) {
            name = req.name();
            if (req.deck() != null) {
                try {
                    deck = Deck.valueOf(req.deck().toUpperCase());
                } catch (IllegalArgumentException ignored) { /* дефолт */ }
            }
        }
        String ownerUserId = oauthUser != null ? (String) oauthUser.getAttribute("_userId") : null;
        Room room = roomService.createRoom(name, deck, ownerUserId);
        return new RoomResponse(room.getId(), room.getName(), room.getDeck().name());
    }

    @GetMapping("/{roomId}")
    public RoomResponse get(@PathVariable String roomId) {
        Room room = roomService.getRoom(roomId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Комната не найдена"));
        return new RoomResponse(room.getId(), room.getName(), room.getDeck().name());
    }

    /** IP клиента с учётом обратного прокси (Railway/nginx ставят X-Forwarded-For). */
    private static String clientIp(HttpServletRequest http) {
        String fwd = http.getHeader("X-Forwarded-For");
        if (fwd != null && !fwd.isBlank()) {
            int comma = fwd.indexOf(',');
            return (comma > 0 ? fwd.substring(0, comma) : fwd).strip();
        }
        return http.getRemoteAddr();
    }
}
