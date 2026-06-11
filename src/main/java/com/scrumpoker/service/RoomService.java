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
import jakarta.annotation.PreDestroy;
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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Service
public class RoomService {

    private static final Logger log = LoggerFactory.getLogger(RoomService.class);
    private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final int MAX_PARTICIPANTS = 100;

    /**
     * Дебаунс записи в БД: изменения накапливаются 500мс, затем одна запись.
     * Это позволяет при пачке кликов (голоса, смена колоды и т.д.) делать
     * 1 DB-запись вместо N, не затрагивая real-time рассылку по WebSocket.
     */
    private static final long PERSIST_DEBOUNCE_MS = 500;
    private final ScheduledExecutorService persistScheduler = Executors.newScheduledThreadPool(1, r -> {
        Thread t = new Thread(r, "persist-debounce");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, ScheduledFuture<?>> pendingPersist = new ConcurrentHashMap<>();

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

    public Optional<Room> getRoom(String id) {
        Room room = rooms.get(id);
        if (room != null) return Optional.of(room);
        return roomRepository.findById(id).flatMap(json -> {
            try {
                RoomSnapshot snap = objectMapper.readValue(json, RoomSnapshot.class);
                Room loaded = snap.toRoom();
                rooms.put(loaded.getId(), loaded);
                return Optional.of(loaded);
            } catch (Exception e) {
                log.warn("Не удалось восстановить комнату {} из БД: {}", id, e.getMessage());
                return Optional.empty();
            }
        });
    }

    /** Добавить участника. Бросает, если комната переполнена. */
    public Participant join(Room room, String name, Participant.Role role) {
        if (room.size() >= MAX_PARTICIPANTS) {
            throw new IllegalStateException("Комната заполнена (максимум " + MAX_PARTICIPANTS + ")");
        }
        String id = generateId();
        // Модератор — только если в комнате ещё нет ни одного модератора (онлайн или офлайн).
        // Это предотвращает появление двух модераторов при реконнекте старого и новом входе.
        boolean hasModerator = room.getParticipants().stream()
                .anyMatch(p -> p.getRole() == Participant.Role.MODERATOR);
        Participant.Role effectiveRole = !hasModerator ? Participant.Role.MODERATOR : role;
        Participant p = new Participant(id, name, effectiveRole);
        room.addParticipant(p);
        return p;
    }

    /** Сколько раз подряд не удалось сохранить в БД — для диагностики деградации персистентности. */
    private final java.util.concurrent.atomic.AtomicInteger persistFailures =
            new java.util.concurrent.atomic.AtomicInteger();

    /**
     * Запланировать сохранение комнаты в БД через PERSIST_DEBOUNCE_MS.
     * Если за это время придут новые изменения — предыдущий таймер отменяется
     * и отсчёт начинается заново. Итого: при «пачке» кликов — одна DB-запись.
     * WebSocket-рассылка клиентам происходит в PokerController сразу, не ждёт персиста.
     */
    public void persistRoom(Room room) {
        ScheduledFuture<?> prev = pendingPersist.put(room.getId(),
                persistScheduler.schedule(() -> {
                    pendingPersist.remove(room.getId());
                    doPersist(room);
                }, PERSIST_DEBOUNCE_MS, TimeUnit.MILLISECONDS));
        if (prev != null) prev.cancel(false);
    }

    /** Немедленная запись — используется при эвикции, остановке сервера и создании с задачами. */
    public void persistRoomNow(Room room) {
        ScheduledFuture<?> pending = pendingPersist.remove(room.getId());
        if (pending != null) pending.cancel(false);
        doPersist(room);
    }

    /** Фактическая запись в БД (вызывается из фонового потока или напрямую). */
    private void doPersist(Room room) {
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
        if (room.getOwnerUserId() != null) {
            try {
                persistSessionHistory(room);
            } catch (Exception e) {
                log.warn("Не удалось обновить историю сессии {}: {}", room.getId(), e.getMessage());
            }
        }
    }

    /**
     * При graceful shutdown — сбрасываем все отложенные записи, чтобы не потерять
     * изменения, которые не успели записаться за 500мс до остановки.
     */
    @PreDestroy
    public void flushPendingPersists() {
        log.info("Сброс {} отложенных записей комнат перед остановкой...", pendingPersist.size());
        rooms.values().forEach(this::persistRoomNow);
        persistScheduler.shutdownNow();
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
        StringBuilder sb = new StringBuilder(6);
        for (int i = 0; i < 6; i++) {
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
}
