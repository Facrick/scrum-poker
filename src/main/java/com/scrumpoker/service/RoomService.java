package com.scrumpoker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scrumpoker.model.Deck;
import com.scrumpoker.model.Participant;
import com.scrumpoker.model.Room;
import com.scrumpoker.persistence.RoomRepository;
import com.scrumpoker.persistence.RoomSnapshot;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class RoomService {

    private static final Logger log = LoggerFactory.getLogger(RoomService.class);
    private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final int MAX_PARTICIPANTS = 100;

    private final SecureRandom random = new SecureRandom();
    private final ObjectMapper objectMapper;
    private final RoomRepository roomRepository;

    @Value("${app.room-ttl-hours:8}")
    private int roomTtlHours;

    private final Map<String, Room> rooms = new ConcurrentHashMap<>();

    public RoomService(ObjectMapper objectMapper, RoomRepository roomRepository) {
        this.objectMapper = objectMapper;
        this.roomRepository = roomRepository;
    }

    /** Загрузить все комнаты из БД при старте сервера. */
    @PostConstruct
    public void loadFromDatabase() {
        int loaded = 0;
        for (String json : roomRepository.findAll()) {
            try {
                RoomSnapshot snap = objectMapper.readValue(json, RoomSnapshot.class);
                Room room = snap.toRoom();
                rooms.put(room.getId(), room);
                loaded++;
            } catch (Exception e) {
                log.warn("Не удалось восстановить комнату из БД: {}", e.getMessage());
            }
        }
        if (loaded > 0) log.info("Восстановлено {} комнат из БД", loaded);
    }

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
        // Первый вошедший (или единственный онлайн) становится модератором.
        boolean anyOnline = room.getParticipants().stream().anyMatch(Participant::isOnline);
        Participant.Role effectiveRole = !anyOnline ? Participant.Role.MODERATOR : role;
        Participant p = new Participant(id, name, effectiveRole);
        room.addParticipant(p);
        return p;
    }

    /** Сохранить снимок комнаты в БД. Вызывается при каждом broadcast(). */
    public void persistRoom(Room room) {
        try {
            String json = objectMapper.writeValueAsString(RoomSnapshot.from(room));
            roomRepository.save(room.getId(), json);
        } catch (Exception e) {
            log.warn("Не удалось сохранить комнату {} в БД: {}", room.getId(), e.getMessage());
        }
    }

    public void removeEmptyRoom(Room room) {
        rooms.remove(room.getId());
        roomRepository.delete(room.getId());
    }

    /** Каждые 30 минут удаляет комнаты старше roomTtlHours. */
    @Scheduled(fixedDelay = 30 * 60 * 1000)
    public void evictStaleRooms() {
        Instant cutoff = Instant.now().minus(roomTtlHours, ChronoUnit.HOURS);
        List<String> toRemove = new ArrayList<>();
        rooms.values().removeIf(room -> {
            if (room.getCreatedAt().isBefore(cutoff)) {
                toRemove.add(room.getId());
                return true;
            }
            return false;
        });
        if (!toRemove.isEmpty()) {
            roomRepository.deleteAll(toRemove);
            log.info("Удалено {} устаревших комнат", toRemove.size());
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
