package com.scrumpoker.service;

import com.scrumpoker.model.Deck;
import com.scrumpoker.model.Participant;
import com.scrumpoker.model.Room;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class RoomService {

    private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final int MAX_PARTICIPANTS = 100;
    private final SecureRandom random = new SecureRandom();

    private final Map<String, Room> rooms = new ConcurrentHashMap<>();

    public Room createRoom(String name, Deck deck) {
        String id = generateId();
        Room room = new Room(id, name == null || name.isBlank() ? "Scrum Poker" : name,
                deck == null ? Deck.FIBONACCI : deck);
        rooms.put(id, room);
        return room;
    }

    public Optional<Room> getRoom(String id) {
        return Optional.ofNullable(rooms.get(id));
    }

    /** Добавить участника. Бросает, если комната переполнена. */
    public Participant join(Room room, String name, Participant.Role role) {
        if (room.size() >= MAX_PARTICIPANTS) {
            throw new IllegalStateException("Комната заполнена (максимум " + MAX_PARTICIPANTS + ")");
        }
        String id = generateId();
        // Первый вошедший становится модератором.
        Participant.Role effectiveRole = room.getParticipants().isEmpty()
                ? Participant.Role.MODERATOR : role;
        Participant p = new Participant(id, name, effectiveRole);
        room.addParticipant(p);
        return p;
    }

    public void removeEmptyRoom(Room room) {
        if (room.size() == 0) {
            rooms.remove(room.getId());
        }
    }

    private String generateId() {
        StringBuilder sb = new StringBuilder(8);
        for (int i = 0; i < 8; i++) {
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
}
