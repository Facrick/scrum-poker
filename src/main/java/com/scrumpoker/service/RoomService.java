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
    private final RateLimiter rateLimiter;

    @Value("${app.room-ttl-hours:8}")
    private int roomTtlHours;

    private final Map<String, Room> rooms = new ConcurrentHashMap<>();

    public RoomService(ObjectMapper objectMapper, RoomRepository roomRepository, RateLimiter rateLimiter) {
        this.objectMapper = objectMapper;
        this.roomRepository = roomRepository;
        this.rateLimiter = rateLimiter;
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

    /** Сколько раз подряд не удалось сохранить в БД — для диагностики деградации персистентности. */
    private final java.util.concurrent.atomic.AtomicInteger persistFailures =
            new java.util.concurrent.atomic.AtomicInteger();

    /** Сохранить снимок комнаты в БД. Вызывается при каждом broadcast(). */
    public void persistRoom(Room room) {
        try {
            String json = objectMapper.writeValueAsString(RoomSnapshot.from(room));
            roomRepository.save(room.getId(), json);
            persistFailures.set(0);
        } catch (Exception e) {
            int n = persistFailures.incrementAndGet();
            // Раньше ошибки молча гасились на WARN — потеря персистентности оставалась незамеченной.
            // Теперь логируем на ERROR и считаем подряд идущие сбои.
            log.error("Не удалось сохранить комнату {} в БД (подряд сбоев: {}): {}",
                    room.getId(), n, e.getMessage());
            if (n == 1) log.error("Полная трассировка первого сбоя персистентности:", e);
        }
    }

    /** Число подряд идущих сбоев записи в БД (0 — последняя запись успешна). */
    public int getConsecutivePersistFailures() {
        return persistFailures.get();
    }

    public void removeEmptyRoom(Room room) {
        rooms.remove(room.getId());
        roomRepository.delete(room.getId());
    }

    /**
     * Каждые 30 минут удаляет комнаты, в которых не было активности дольше roomTtlHours.
     * Раньше отсчёт шёл от createdAt — активная комната могла быть удалена прямо во время сессии.
     */
    @Scheduled(fixedDelay = 30 * 60 * 1000)
    public void evictStaleRooms() {
        Instant cutoff = Instant.now().minus(roomTtlHours, ChronoUnit.HOURS);
        List<String> toRemove = new ArrayList<>();
        rooms.values().removeIf(room -> {
            if (room.getLastActivityAt().isBefore(cutoff)) {
                toRemove.add(room.getId());
                return true;
            }
            return false;
        });
        if (!toRemove.isEmpty()) {
            roomRepository.deleteAll(toRemove);
            log.info("Удалено {} устаревших комнат", toRemove.size());
        }
        // Подчищаем устаревшие окна rate-limiter, чтобы карта не росла.
        rateLimiter.evictOlderThan(60 * 60 * 1000L);
    }

    private String generateId() {
        StringBuilder sb = new StringBuilder(8);
        for (int i = 0; i < 8; i++) {
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
}
