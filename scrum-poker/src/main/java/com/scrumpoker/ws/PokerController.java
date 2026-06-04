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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
        // Голос принимается только если значение есть в колоде.
        if (!room.getDeck().getCards().contains(msg.value())) return;
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
