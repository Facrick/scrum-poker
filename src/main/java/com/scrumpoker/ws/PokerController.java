package com.scrumpoker.ws;

import com.scrumpoker.dto.RoomStateDto;
import com.scrumpoker.model.BacklogItem;
import com.scrumpoker.model.Participant;
import com.scrumpoker.model.Room;
import com.scrumpoker.service.RateLimiter;
import com.scrumpoker.service.RoomService;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.stereotype.Controller;

import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.support.MessageHeaderAccessor;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Controller
public class PokerController {

    // Не более 20 WS-сообщений на сессию за 10 секунд (защита от флуда голосами/таймером).
    private static final int WS_LIMIT = 20;
    private static final long WS_WINDOW_MS = 10_000;

    private final RoomService roomService;
    private final SimpMessagingTemplate messaging;
    private final RateLimiter rateLimiter;

    // sessionId -> (roomId, participantId) для авторизации и обработки отключений.
    private final Map<String, String[]> sessions = new ConcurrentHashMap<>();

    public PokerController(RoomService roomService, SimpMessagingTemplate messaging, RateLimiter rateLimiter) {
        this.roomService = roomService;
        this.messaging = messaging;
        this.rateLimiter = rateLimiter;
    }

    @MessageMapping("/room/{roomId}/join")
    @SendToUser(destinations = "/queue/me", broadcast = false)
    public Map<String, String> join(@DestinationVariable String roomId,
                                    @Payload Messages.JoinMessage msg,
                                    SimpMessageHeaderAccessor headers) {
        Room room = roomService.getRoom(roomId).orElse(null);
        if (room == null) {
            return Map.of("error", "Комната не найдена");
        }
        String sessionId = headers.getSessionId();
        if (!rateLimiter.allow("ws:" + sessionId, WS_LIMIT, WS_WINDOW_MS)) {
            return Map.of("error", "Слишком много запросов");
        }

        String cleanName = sanitizeName(msg.name());

        // Вся логика find-or-create синхронизирована по комнате, чтобы два
        // одновременных join'а не создали дубль и не получили один participantId.
        synchronized (room) {
            // 1. Реконнект по сохранённому participantId.
            if (msg.existingId() != null && !msg.existingId().isBlank()) {
                Participant existing = room.getParticipant(msg.existingId());
                if (existing != null) {
                    // Если в комнате уже есть онлайн-модератор (другой) — понижаем до PLAYER,
                    // чтобы не создавать двух модераторов при реконнекте старого.
                    if (existing.getRole() == Participant.Role.MODERATOR) {
                        boolean anotherModOnline = room.getParticipants().stream()
                                .anyMatch(p -> p.isOnline()
                                        && p.getRole() == Participant.Role.MODERATOR
                                        && !p.getId().equals(existing.getId()));
                        if (anotherModOnline) existing.setRole(Participant.Role.PLAYER);
                    }
                    bindSession(sessionId, room, existing);
                    broadcast(room);
                    return Map.of("participantId", existing.getId(), "role", existing.getRole().name());
                }
            }

            // 2. existingId не сработал — офлайн-участник с тем же именем (localStorage очищен).
            Participant byName = room.getParticipants().stream()
                    .filter(p -> !p.isOnline() && p.getName().equalsIgnoreCase(cleanName))
                    .findFirst().orElse(null);
            if (byName != null) {
                if (byName.getRole() == Participant.Role.MODERATOR) {
                    boolean anotherModOnline = room.getParticipants().stream()
                            .anyMatch(p -> p.isOnline()
                                    && p.getRole() == Participant.Role.MODERATOR
                                    && !p.getId().equals(byName.getId()));
                    if (anotherModOnline) byName.setRole(Participant.Role.PLAYER);
                }
                bindSession(sessionId, room, byName);
                broadcast(room);
                return Map.of("participantId", byName.getId(), "role", byName.getRole().name());
            }

            // 3. Новый участник — убираем stale-записи офлайн с тем же именем.
            room.getParticipants().stream()
                    .filter(p -> !p.isOnline() && p.getName().equalsIgnoreCase(cleanName))
                    .map(Participant::getId)
                    .toList()
                    .forEach(room::removeParticipant);

            Participant.Role role = parseRole(msg.role());
            Participant p;
            try {
                p = roomService.join(room, cleanName, role);
            } catch (IllegalStateException full) {
                return Map.of("error", full.getMessage());
            }
            bindSession(sessionId, room, p);
            broadcast(room);
            return Map.of("participantId", p.getId(), "role", p.getRole().name());
        }
    }

    @MessageMapping("/room/{roomId}/vote")
    public void vote(@DestinationVariable String roomId, @Payload Messages.VoteMessage msg,
                     SimpMessageHeaderAccessor headers) {
        Room room = resolve(roomId, headers);
        if (room == null) return;
        // Голосует только тот, кто привязан к этой сессии — payload.participantId не доверяем.
        Participant p = actor(room, headers);
        if (p == null || p.getRole() == Participant.Role.OBSERVER || room.isRevealed()) return;
        if (!room.getEffectiveCards().contains(msg.value())) return;
        p.setVote(msg.value());
        broadcast(room);
    }

    @MessageMapping("/room/{roomId}/reveal")
    public void reveal(@DestinationVariable String roomId, @Payload Messages.ModeratorAction msg,
                       SimpMessageHeaderAccessor headers) {
        Room room = requireModerator(roomId, headers);
        if (room == null) return;
        room.setRevealed(true);
        broadcast(room);
    }

    @MessageMapping("/room/{roomId}/reset")
    public void reset(@DestinationVariable String roomId, @Payload Messages.ModeratorAction msg,
                      SimpMessageHeaderAccessor headers) {
        Room room = requireModerator(roomId, headers);
        if (room == null) return;
        room.resetRound();
        broadcast(room);
    }

    @MessageMapping("/room/{roomId}/story")
    public void setStory(@DestinationVariable String roomId, @Payload Messages.SetStoryMessage msg,
                         SimpMessageHeaderAccessor headers) {
        Room room = requireModerator(roomId, headers);
        if (room == null) return;
        String title = msg.story() == null ? "" : msg.story().strip();
        if (title.isEmpty()) return;
        if (title.length() > 120) title = title.substring(0, 120);
        BacklogItem item = new BacklogItem(title);
        if (room.getBacklog().size() < 200) room.getBacklog().add(item);
        room.setActiveItemId(item.getId());
        room.setCurrentStory(title);
        room.resetRound();
        broadcast(room);
    }

    @MessageMapping("/room/{roomId}/deck")
    public void setDeck(@DestinationVariable String roomId, @Payload Messages.SetDeckMessage msg,
                        SimpMessageHeaderAccessor headers) {
        Room room = requireModerator(roomId, headers);
        if (room == null || msg.deck() == null) return;
        com.scrumpoker.model.Deck newDeck;
        try {
            newDeck = com.scrumpoker.model.Deck.valueOf(msg.deck().toUpperCase());
        } catch (IllegalArgumentException e) {
            return;
        }
        if (newDeck == com.scrumpoker.model.Deck.CUSTOM) return;
        room.setDeck(newDeck);
        room.resetRound();
        broadcast(room);
    }

    @MessageMapping("/room/{roomId}/customdeck")
    public void setCustomDeck(@DestinationVariable String roomId, @Payload Messages.SetCustomDeckMessage msg,
                              SimpMessageHeaderAccessor headers) {
        Room room = requireModerator(roomId, headers);
        if (room == null) return;
        if (msg.cards() == null || msg.cards().isEmpty()) return;
        java.util.List<String> cards = msg.cards().stream()
                .map(String::strip)
                .filter(s -> !s.isEmpty() && s.length() <= 8)
                .distinct()
                .limit(20)
                .collect(java.util.stream.Collectors.toList());
        if (cards.isEmpty()) return;
        room.setDeck(com.scrumpoker.model.Deck.CUSTOM);
        room.setCustomCards(cards);
        room.resetRound();
        broadcast(room);
    }

    @MessageMapping("/room/{roomId}/estimate")
    public void setFinalEstimate(@DestinationVariable String roomId, @Payload Messages.SetFinalEstimateMessage msg,
                                 SimpMessageHeaderAccessor headers) {
        Room room = requireModerator(roomId, headers);
        if (room == null || !room.isRevealed()) return;
        String rawEst = msg.estimate() == null ? null : msg.estimate().strip();
        final String est = (rawEst != null && rawEst.length() > 16) ? rawEst.substring(0, 16) : rawEst;
        room.setFinalEstimate(est);
        if (est != null && room.getActiveItemId() != null) {
            room.getBacklog().stream()
                    .filter(i -> i.getId().equals(room.getActiveItemId()))
                    .findFirst()
                    .ifPresent(i -> i.setEstimate(est));
        }
        broadcast(room);
    }

    // ---- Таймер ----

    @MessageMapping("/room/{roomId}/timer/start")
    public void startTimer(@DestinationVariable String roomId, @Payload Messages.StartTimerMessage msg,
                           SimpMessageHeaderAccessor headers) {
        Room room = requireModerator(roomId, headers);
        if (room == null) return;
        if (msg.seconds() <= 0 || msg.seconds() > 600) return;
        room.setTimerSeconds(msg.seconds());
        room.setTimerStartedAt(Instant.now());
        broadcast(room);
    }

    @MessageMapping("/room/{roomId}/timer/stop")
    public void stopTimer(@DestinationVariable String roomId, @Payload Messages.StopTimerMessage msg,
                          SimpMessageHeaderAccessor headers) {
        Room room = requireModerator(roomId, headers);
        if (room == null) return;
        room.setTimerStartedAt(null);
        broadcast(room);
    }

    // ---- Бэклог ----

    @MessageMapping("/room/{roomId}/backlog/add")
    public void addBacklogItem(@DestinationVariable String roomId, @Payload Messages.AddBacklogItemMessage msg,
                               SimpMessageHeaderAccessor headers) {
        Room room = requireModerator(roomId, headers);
        if (room == null) return;
        if (msg.title() == null || msg.title().isBlank()) return;
        String title = msg.title().strip();
        if (title.length() > 120) title = title.substring(0, 120);
        if (room.getBacklog().size() >= 200) return;
        room.getBacklog().add(new BacklogItem(title));
        broadcast(room);
    }

    @MessageMapping("/room/{roomId}/backlog/remove")
    public void removeBacklogItem(@DestinationVariable String roomId, @Payload Messages.RemoveBacklogItemMessage msg,
                                  SimpMessageHeaderAccessor headers) {
        Room room = requireModerator(roomId, headers);
        if (room == null || msg.itemId() == null) return;
        room.getBacklog().removeIf(i -> i.getId().equals(msg.itemId()));
        if (msg.itemId().equals(room.getActiveItemId())) room.setActiveItemId(null);
        broadcast(room);
    }

    @MessageMapping("/room/{roomId}/backlog/activate")
    public void activateBacklogItem(@DestinationVariable String roomId, @Payload Messages.ActivateBacklogItemMessage msg,
                                    SimpMessageHeaderAccessor headers) {
        Room room = requireModerator(roomId, headers);
        if (room == null || msg.itemId() == null) return;
        BacklogItem item = room.getBacklog().stream()
                .filter(i -> i.getId().equals(msg.itemId()))
                .findFirst().orElse(null);
        if (item == null) return;
        room.setActiveItemId(item.getId());
        room.setCurrentStory(item.getTitle());
        room.resetRound();
        broadcast(room);
    }


    @MessageMapping("/room/{roomId}/rename")
    public void rename(@DestinationVariable String roomId,
                       @Payload Messages.RenameMessage msg,
                       SimpMessageHeaderAccessor headers) {
        if (!rateLimiter.allow("ws:" + headers.getSessionId(), WS_LIMIT, WS_WINDOW_MS)) return;
        var entry = sessions.get(headers.getSessionId());
        if (entry == null) return;
        Room room = roomService.getRoom(roomId).orElse(null);
        if (room == null) return;
        Participant sender = room.getParticipant(entry.participantId());
        if (sender == null || sender.getRole() != Participant.Role.MODERATOR) return;
        String name = msg.name() == null ? "" : msg.name().trim();
        if (name.isEmpty() || name.length() > 60) return;
        room.setName(name);
        broadcast(room);
    }

    @MessageMapping("/room/{roomId}/transfer")
    public void transferModerator(@DestinationVariable String roomId, @Payload Messages.TransferMessage msg,
                                  SimpMessageHeaderAccessor headers) {
        Room room = requireModerator(roomId, headers);
        if (room == null || msg.targetId() == null) return;
        Participant target = room.getParticipant(msg.targetId());
        if (target == null || target.getRole() == Participant.Role.MODERATOR) return;
        Participant current = actor(room, headers);
        if (current == null) return;
        // Текущий модератор → PLAYER, целевой участник → MODERATOR
        current.setRole(Participant.Role.PLAYER);
        target.setRole(Participant.Role.MODERATOR);
        broadcast(room);
    }

    @MessageMapping("/room/{roomId}/kick")
    public void kick(@DestinationVariable String roomId, @Payload Messages.KickMessage msg,
                     SimpMessageHeaderAccessor headers) {
        Room room = requireModerator(roomId, headers);
        if (room == null || msg.targetId() == null) return;
        Participant target = room.getParticipant(msg.targetId());
        room.removeParticipant(msg.targetId());
        // Снимаем привязку сессии и уведомляем выгнанного через его личную очередь.
        if (target != null && target.getSessionId() != null) {
            String kickedSession = target.getSessionId();
            sessions.remove(kickedSession);
            // Отправляем error-сообщение в /user/queue/me выгнанного участника.
            // Клиент обработает его в onMe() и вернётся в лобби.
            SimpMessageHeaderAccessor ha = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
            ha.setSessionId(kickedSession);
            ha.setLeaveMutable(true);
            messaging.convertAndSendToUser(kickedSession, "/queue/me",
                    Map.of("error", "Вы были удалены из комнаты модератором"),
                    ha.getMessageHeaders());
        }
        broadcast(room);
    }

    /** Вызывается из DisconnectListener при разрыве WebSocket-сессии. */
    void handleDisconnect(String sessionId) {
        String[] info = sessions.remove(sessionId);
        if (info == null) return;
        roomService.getRoom(info[0]).ifPresent(room -> {
            Participant p = room.getParticipant(info[1]);
            if (p == null) return;
            // Гасим офлайн только если это всё ещё ТЕКУЩАЯ сессия участника.
            // Иначе запоздавший disconnect старой сессии погасил бы уже переподключившегося.
            if (sessionId.equals(p.getSessionId())) {
                p.setOnline(false);
                p.setSessionId(null);
                broadcast(room);
            }
        });
    }

    // ---- Вспомогательные ----

    private void bindSession(String sessionId, Room room, Participant p) {
        p.setOnline(true);
        p.setSessionId(sessionId);
        sessions.put(sessionId, new String[]{room.getId(), p.getId()});
    }

    /** Резолвит комнату и применяет WS-rate-limit. null — если нет комнаты или лимит превышен. */
    private Room resolve(String roomId, SimpMessageHeaderAccessor headers) {
        if (!rateLimiter.allow("ws:" + headers.getSessionId(), WS_LIMIT, WS_WINDOW_MS)) return null;
        return roomService.getRoom(roomId).orElse(null);
    }

    /** Участник, привязанный к текущей сессии в данной комнате (источник истины для авторизации). */
    private Participant actor(Room room, SimpMessageHeaderAccessor headers) {
        String[] info = sessions.get(headers.getSessionId());
        if (info == null || !info[0].equals(room.getId())) return null;
        return room.getParticipant(info[1]);
    }

    /** Возвращает комнату только если сессия принадлежит модератору; иначе null. */
    private Room requireModerator(String roomId, SimpMessageHeaderAccessor headers) {
        Room room = resolve(roomId, headers);
        if (room == null) return null;
        Participant p = actor(room, headers);
        return (p != null && p.getRole() == Participant.Role.MODERATOR) ? room : null;
    }

    private void broadcast(Room room) {
        room.touch();
        roomService.persistRoom(room);
        messaging.convertAndSend("/topic/room/" + room.getId(), RoomStateDto.from(room));
    }

    private Participant.Role parseRole(String role) {
        if (role == null) return Participant.Role.PLAYER;
        try {
            return Participant.Role.valueOf(role.toUpperCase());
        } catch (IllegalArgumentException e) {
            return Participant.Role.PLAYER;
        }
    }

    /**
     * Чистит имя: убирает управляющие символы, RTL/LTR-override и zero-width,
     * схлопывает пробелы, ограничивает длину.
     */
    private String sanitizeName(String name) {
        if (name == null || name.isBlank()) return "Аноним";
        StringBuilder sb = new StringBuilder(name.length());
        name.codePoints().forEach(cp -> {
            int type = Character.getType(cp);
            boolean control = type == Character.CONTROL || type == Character.FORMAT
                    || type == Character.PRIVATE_USE || type == Character.SURROGATE;
            boolean bidi = (cp >= 0x202A && cp <= 0x202E)   // LRE..RLO
                    || (cp >= 0x2066 && cp <= 0x2069)        // LRI..PDI
                    || cp == 0x200E || cp == 0x200F          // LRM/RLM
                    || cp == 0xFEFF || cp == 0x200B;         // BOM / zero-width space
            if (!control && !bidi) sb.appendCodePoint(cp);
        });
        String cleaned = sb.toString().replaceAll("\\s+", " ").strip();
        if (cleaned.isBlank()) return "Аноним";
        return cleaned.length() > 40 ? cleaned.substring(0, 40) : cleaned;
    }
}
