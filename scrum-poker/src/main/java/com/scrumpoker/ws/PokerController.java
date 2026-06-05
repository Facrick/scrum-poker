package com.scrumpoker.ws;

import com.scrumpoker.dto.RoomStateDto;
import com.scrumpoker.model.Participant;
import com.scrumpoker.model.Room;
import com.scrumpoker.service.RoomService;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import com.scrumpoker.model.BacklogItem;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Controller
public class PokerController {

    private final RoomService roomService;
    private final SimpMessagingTemplate messaging;

    // sessionId -> (roomId, participantId) для корректной обработки отключений.
    private final Map<String, String[]> sessions = new ConcurrentHashMap<>();

    public PokerController(RoomService roomService, SimpMessagingTemplate messaging) {
        this.roomService = roomService;
        this.messaging = messaging;
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

        Participant.Role role = parseRole(msg.role());
        Participant p;
        try {
            p = roomService.join(room, sanitizeName(msg.name()), role);
        } catch (IllegalStateException full) {
            return Map.of("error", full.getMessage());
        }

        sessions.put(headers.getSessionId(), new String[]{roomId, p.getId()});
        broadcast(room);
        // Ответ уходит лично подключившемуся клиенту на /user/queue/me.
        return Map.of("participantId", p.getId(), "role", p.getRole().name());
    }

    @MessageMapping("/room/{roomId}/vote")
    public void vote(@DestinationVariable String roomId, @Payload Messages.VoteMessage msg) {
        Room room = roomService.getRoom(roomId).orElse(null);
        if (room == null) return;
        Participant p = room.getParticipant(msg.participantId());
        if (p == null || p.getRole() == Participant.Role.OBSERVER || room.isRevealed()) return;
        // Голос принимается только если значение есть в текущей колоде.
        if (!room.getEffectiveCards().contains(msg.value())) return;
        p.setVote(msg.value());
        broadcast(room);
    }

    @MessageMapping("/room/{roomId}/reveal")
    public void reveal(@DestinationVariable String roomId, @Payload Messages.ModeratorAction msg) {
        Room room = roomService.getRoom(roomId).orElse(null);
        if (room == null || !isModerator(room, msg.participantId())) return;
        room.setRevealed(true);
        broadcast(room);
    }

    @MessageMapping("/room/{roomId}/reset")
    public void reset(@DestinationVariable String roomId, @Payload Messages.ModeratorAction msg) {
        Room room = roomService.getRoom(roomId).orElse(null);
        if (room == null || !isModerator(room, msg.participantId())) return;
        room.resetRound();
        broadcast(room);
    }

    @MessageMapping("/room/{roomId}/story")
    public void setStory(@DestinationVariable String roomId, @Payload Messages.SetStoryMessage msg) {
        Room room = roomService.getRoom(roomId).orElse(null);
        if (room == null || !isModerator(room, msg.participantId())) return;
        room.setCurrentStory(msg.story() == null ? "" : msg.story().strip());
        broadcast(room);
    }

    @MessageMapping("/room/{roomId}/deck")
    public void setDeck(@DestinationVariable String roomId, @Payload Messages.SetDeckMessage msg) {
        Room room = roomService.getRoom(roomId).orElse(null);
        if (room == null || !isModerator(room, msg.participantId()) || msg.deck() == null) return;
        com.scrumpoker.model.Deck newDeck;
        try {
            newDeck = com.scrumpoker.model.Deck.valueOf(msg.deck().toUpperCase());
        } catch (IllegalArgumentException e) {
            return; // неизвестная колода — игнорируем
        }
        // Нельзя переключиться на CUSTOM через этот хендлер — только через /customdeck
        if (newDeck == com.scrumpoker.model.Deck.CUSTOM) return;
        room.setDeck(newDeck);
        room.resetRound(); // прежние голоса не относятся к новой колоде
        broadcast(room);
    }

    @MessageMapping("/room/{roomId}/customdeck")
    public void setCustomDeck(@DestinationVariable String roomId, @Payload Messages.SetCustomDeckMessage msg) {
        Room room = roomService.getRoom(roomId).orElse(null);
        if (room == null || !isModerator(room, msg.participantId())) return;
        if (msg.cards() == null || msg.cards().isEmpty()) return;
        // Ограничиваем: не более 20 карт, каждая не длиннее 8 символов
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
    public void setFinalEstimate(@DestinationVariable String roomId, @Payload Messages.SetFinalEstimateMessage msg) {
        Room room = roomService.getRoom(roomId).orElse(null);
        if (room == null || !isModerator(room, msg.participantId())) return;
        if (!room.isRevealed()) return;
        String rawEst = msg.estimate() == null ? null : msg.estimate().strip();
        final String est = (rawEst != null && rawEst.length() > 16) ? rawEst.substring(0, 16) : rawEst;
        room.setFinalEstimate(est);
        // Сохраняем оценку в активный элемент бэклога
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
    public void startTimer(@DestinationVariable String roomId, @Payload Messages.StartTimerMessage msg) {
        Room room = roomService.getRoom(roomId).orElse(null);
        if (room == null || !isModerator(room, msg.participantId())) return;
        if (msg.seconds() <= 0 || msg.seconds() > 600) return;
        room.setTimerSeconds(msg.seconds());
        room.setTimerStartedAt(Instant.now());
        broadcast(room);
    }

    @MessageMapping("/room/{roomId}/timer/stop")
    public void stopTimer(@DestinationVariable String roomId, @Payload Messages.StopTimerMessage msg) {
        Room room = roomService.getRoom(roomId).orElse(null);
        if (room == null || !isModerator(room, msg.participantId())) return;
        room.setTimerStartedAt(null);
        broadcast(room);
    }

    // ---- Бэклог ----

    @MessageMapping("/room/{roomId}/backlog/add")
    public void addBacklogItem(@DestinationVariable String roomId, @Payload Messages.AddBacklogItemMessage msg) {
        Room room = roomService.getRoom(roomId).orElse(null);
        if (room == null || !isModerator(room, msg.participantId())) return;
        if (msg.title() == null || msg.title().isBlank()) return;
        String title = msg.title().strip();
        if (title.length() > 120) title = title.substring(0, 120);
        if (room.getBacklog().size() >= 200) return;
        room.getBacklog().add(new BacklogItem(title));
        broadcast(room);
    }

    @MessageMapping("/room/{roomId}/backlog/remove")
    public void removeBacklogItem(@DestinationVariable String roomId, @Payload Messages.RemoveBacklogItemMessage msg) {
        Room room = roomService.getRoom(roomId).orElse(null);
        if (room == null || !isModerator(room, msg.participantId())) return;
        room.getBacklog().removeIf(i -> i.getId().equals(msg.itemId()));
        if (msg.itemId().equals(room.getActiveItemId())) room.setActiveItemId(null);
        broadcast(room);
    }

    @MessageMapping("/room/{roomId}/backlog/activate")
    public void activateBacklogItem(@DestinationVariable String roomId, @Payload Messages.ActivateBacklogItemMessage msg) {
        Room room = roomService.getRoom(roomId).orElse(null);
        if (room == null || !isModerator(room, msg.participantId())) return;
        BacklogItem item = room.getBacklog().stream()
                .filter(i -> i.getId().equals(msg.itemId()))
                .findFirst().orElse(null);
        if (item == null) return;
        room.setActiveItemId(item.getId());
        room.setCurrentStory(item.getTitle());
        room.resetRound();
        broadcast(room);
    }

    @MessageMapping("/room/{roomId}/kick")
    public void kick(@DestinationVariable String roomId, @Payload Messages.KickMessage msg) {
        Room room = roomService.getRoom(roomId).orElse(null);
        if (room == null || !isModerator(room, msg.participantId())) return;
        room.removeParticipant(msg.targetId());
        broadcast(room);
    }

    /** Вызывается из DisconnectListener при разрыве WebSocket-сессии. */
    void handleDisconnect(String sessionId) {
        String[] info = sessions.remove(sessionId);
        if (info == null) return;
        roomService.getRoom(info[0]).ifPresent(room -> {
            room.removeParticipant(info[1]);
            if (room.size() == 0) {
                roomService.removeEmptyRoom(room);
            } else {
                broadcast(room);
            }
        });
    }

    private void broadcast(Room room) {
        messaging.convertAndSend("/topic/room/" + room.getId(), RoomStateDto.from(room));
    }

    private boolean isModerator(Room room, String participantId) {
        Participant p = room.getParticipant(participantId);
        return p != null && p.getRole() == Participant.Role.MODERATOR;
    }

    private Participant.Role parseRole(String role) {
        if (role == null) return Participant.Role.PLAYER;
        try {
            return Participant.Role.valueOf(role.toUpperCase());
        } catch (IllegalArgumentException e) {
            return Participant.Role.PLAYER;
        }
    }

    private String sanitizeName(String name) {
        if (name == null || name.isBlank()) return "Аноним";
        String trimmed = name.strip();
        return trimmed.length() > 40 ? trimmed.substring(0, 40) : trimmed;
    }
}
