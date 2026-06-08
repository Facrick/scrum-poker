package com.scrumpoker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scrumpoker.account.SessionHistory;
import com.scrumpoker.account.SessionHistoryRepository;
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
    private final SessionHistoryRepository sessionHistoryRepository;
    private final RateLimiter rateLimiter;

    @Value("${app.room-ttl-hours:8}")
    private int roomTtlHours;

    private final Map<String, Room> rooms = new ConcurrentHashMap<>();

    public RoomService(ObjectMapper objectMapper, RoomRepository roomRepository,
                       SessionHistoryRepository sessionHistoryRepository, RateLimiter rateLimiter) {
        this.objectMapper = objectMapper;
        this.roomRepository = roomRepository;
        this.sessionHistoryRepository = sessionHistoryRepository;
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
        return createRoom(name, deck, null);
    }

    public Room createRoom(String name, Deck deck, String ownerUserId) {
        String id = generateId();
        Room room = new Room(id, name == null || name.isBlank() ? "Scrum Poker" : name,
                deck == null ? Deck.FIBONACCI : deck);
        room.setOwnerUserId(ownerUserId);
        rooms.put(id, room);
        return room;
    }

    /**
     * Создать комнату с заранее подготовленным бэклогом (из ЛК).
     * Первая задача становится активной. Пустые/слишком длинные строки чистятся,
     * лимит — 200 задач.
     */
    public Room createRoom(String name, Deck deck, String ownerUserId, java.util.List<String> tasks) {
        Room room = createRoom(name, deck, ownerUserId);
        if (tasks != null) {
            for (String raw : tasks) {
                if (room.getBacklog().size() >= 200) break;
                if (raw == null) continue;
                String t = raw.strip();
                if (t.isEmpty()) continue;
                if (t.length() > 120) t = t.substring(0, 120);
                room.getBacklog().add(new com.scrumpoker.model.BacklogItem(t));
            }
            if (!room.getBacklog().isEmpty()) {
                com.scrumpoker.model.BacklogItem first = room.getBacklog().get(0);
                room.setActiveItemId(first.getId());
                room.setCurrentStory(first.getTitle());
            }
            persistRoom(room);
        }
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
        // Ведущим автоматически становится только САМЫЙ первый участник (создатель
        // комнаты). Остальные входят в запрошенной роли — даже если ведущего нет
        // онлайн. Права ведущего передаются дальше только вручную.
        boolean firstEver = room.getParticipants().isEmpty();
        Participant.Role effectiveRole = firstEver ? Participant.Role.MODERATOR : role;
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
            log.error("Не удалось сохранить комнату {} в БД (подряд сбоев: {}): {}",
                    room.getId(), n, e.getMessage());
            if (n == 1) log.error("Полная трассировка первого сбоя персистентности:", e);
        }
        // История сессии обновляется независимо, только для комнат модераторов
        if (room.getOwnerUserId() != null) {
            try {
                persistSessionHistory(room);
            } catch (Exception e) {
                log.warn("Не удалось обновить историю сессии {}: {}", room.getId(), e.getMessage());
            }
        }
    }

    private void persistSessionHistory(Room room) {
        int taskCount = room.getBacklog().size();
        int estimatedCount = (int) room.getBacklog().stream()
                .filter(i -> i.getEstimate() != null)
                .count();
        sessionHistoryRepository.upsert(new SessionHistory(
                room.getId(),
                room.getOwnerUserId(),
                room.getName(),
                room.getParticipants().size(),
                taskCount,
                estimatedCount,
                room.getCreatedAt(),
                room.getLastActivityAt()
        ));
    }

    /** Число подряд идущих сбоев записи в БД (0 — последняя запись успешна). */
    public int getConsecutivePersistFailures() {
        return persistFailures.get();
    }

    /**
     * Переименовать сессию, принадлежащую пользователю. Обновляет историю и,
     * если комната ещё жива в памяти, её имя + снимок. Возвращает false, если
     * сессия не найдена или не принадлежит пользователю.
     */
    public boolean renameOwnedSession(String roomId, String userId, String name) {
        Room room = rooms.get(roomId);
        boolean ownsLiveRoom = room != null && userId.equals(room.getOwnerUserId());
        int updated = sessionHistoryRepository.updateName(roomId, userId, name);
        if (ownsLiveRoom) {
            room.setName(name);
            persistRoom(room); // заодно синхронизирует room_name в истории
        }
        return updated > 0 || ownsLiveRoom;
    }

    /**
     * Удалить сессию пользователя: убрать из истории и, если комната жива,
     * завершить её (память + снимок в БД). Возвращает false, если удалять нечего.
     */
    public boolean deleteOwnedSession(String roomId, String userId) {
        Room room = rooms.get(roomId);
        boolean ownsLiveRoom = room != null && userId.equals(room.getOwnerUserId());
        int deleted = sessionHistoryRepository.delete(roomId, userId);
        if (ownsLiveRoom) {
            removeEmptyRoom(room); // удаляет из памяти и БД; больше не будет broadcast'ов
        }
        return deleted > 0 || ownsLiveRoom;
    }

    /**
     * Загрузить комнату из памяти или (если уже не активна) из снимка БД.
     * Используется публичной страницей итогов — без проверки владельца.
     */
    public Optional<Room> loadAnyRoom(String roomId) {
        Room live = rooms.get(roomId);
        if (live != null) return Optional.of(live);
        return roomRepository.findById(roomId).flatMap(json -> {
            try {
                return Optional.of(objectMapper.readValue(json, RoomSnapshot.class).toRoom());
            } catch (Exception e) {
                return Optional.empty();
            }
        });
    }

    /**
     * Комната, принадлежащая пользователю (из памяти или снимка БД), для отчётов из ЛК.
     * Optional.empty(), если не найдена или не его.
     */
    public Optional<Room> loadOwnedRoom(String roomId, String userId) {
        return loadAnyRoom(roomId)
                .filter(room -> userId != null && userId.equals(room.getOwnerUserId()));
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
        // Снимки удаляем ТОЛЬКО для анонимных комнат. Сессии модераторов (с
        // ownerUserId) сохраняют снимок как постоянный архив итогов — чтобы
        // отчёт XLS и публичную страницу итогов можно было открыть когда угодно.
        List<String> snapshotsToDelete = new ArrayList<>();
        int[] evicted = {0};
        rooms.values().removeIf(room -> {
            if (room.getLastActivityAt().isBefore(cutoff)) {
                if (room.getOwnerUserId() == null) snapshotsToDelete.add(room.getId());
                evicted[0]++;
                return true; // из памяти убираем в любом случае
            }
            return false;
        });
        if (!snapshotsToDelete.isEmpty()) {
            roomRepository.deleteAll(snapshotsToDelete);
        }
        if (evicted[0] > 0) {
            log.info("Выгружено из памяти {} комнат (снимков удалено: {})",
                    evicted[0], snapshotsToDelete.size());
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
