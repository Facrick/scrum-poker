package com.scrumpoker.controller;

import com.scrumpoker.model.Deck;
import com.scrumpoker.model.Room;
import com.scrumpoker.service.RoomService;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/rooms")
public class RoomController {

    private final RoomService roomService;

    public RoomController(RoomService roomService) {
        this.roomService = roomService;
    }

    public record CreateRoomRequest(@Size(max = 80) String name, String deck) {}
    public record RoomResponse(String roomId, String roomName, String deck) {}

    @PostMapping
    public RoomResponse create(@RequestBody(required = false) CreateRoomRequest req) {
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
        Room room = roomService.createRoom(name, deck);
        return new RoomResponse(room.getId(), room.getName(), room.getDeck().name());
    }

    @GetMapping("/{roomId}")
    public RoomResponse get(@PathVariable String roomId) {
        Room room = roomService.getRoom(roomId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Комната не найдена"));
        return new RoomResponse(room.getId(), room.getName(), room.getDeck().name());
    }
}
