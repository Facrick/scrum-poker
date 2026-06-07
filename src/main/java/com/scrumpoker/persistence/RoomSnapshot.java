package com.scrumpoker.persistence;

import com.scrumpoker.model.BacklogItem;
import com.scrumpoker.model.Deck;
import com.scrumpoker.model.Participant;
import com.scrumpoker.model.Room;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Сериализуемый снимок состояния комнаты для хранения в PostgreSQL.
 * Конвертируется в/из доменного объекта Room.
 */
public record RoomSnapshot(
        String id,
        String name,
        String deck,
        List<String> customCards,
        String currentStory,
        boolean revealed,
        String finalEstimate,
        int timerSeconds,
        Long createdAt,               // epoch millis
        List<ParticipantSnap> participants,
        List<BacklogSnap> backlog,
        String activeItemId,
        String ownerUserId,           // null для анонимных комнат
        boolean async                 // режим async-оценки (#3)
) {

    public record ParticipantSnap(String id, String name, String role, String vote) {}
    public record BacklogSnap(String id, String title, String estimate,
                              int revotes, List<VoteSnap> votes,
                              java.util.Map<String, String> liveVotes, boolean itemRevealed) {}
    public record VoteSnap(String name, String value) {}

    /** Создать снимок из доменного объекта Room. */
    public static RoomSnapshot from(Room room) {
        List<ParticipantSnap> parts = room.getParticipants().stream()
                .map(p -> new ParticipantSnap(p.getId(), p.getName(), p.getRole().name(), p.getVote()))
                .collect(Collectors.toList());

        List<BacklogSnap> bl = room.getBacklog().stream()
                .map(i -> new BacklogSnap(i.getId(), i.getTitle(), i.getEstimate(),
                        i.getRevotes(),
                        i.getVotes().stream()
                                .map(v -> new VoteSnap(v.name(), v.value()))
                                .collect(Collectors.toList()),
                        new java.util.HashMap<>(i.getLiveVotes()),
                        i.isRevealed()))
                .collect(Collectors.toList());

        return new RoomSnapshot(
                room.getId(),
                room.getName(),
                room.getDeck().name(),
                room.getCustomCards(),
                room.getCurrentStory(),
                room.isRevealed(),
                room.getFinalEstimate(),
                room.getTimerSeconds(),
                room.getCreatedAt().toEpochMilli(),
                parts,
                bl,
                room.getActiveItemId(),
                room.getOwnerUserId(),
                room.isAsync()
        );
    }

    /** Восстановить доменный объект Room из снимка. */
    public Room toRoom() {
        Deck deckEnum;
        try { deckEnum = Deck.valueOf(deck); } catch (Exception e) { deckEnum = Deck.FIBONACCI; }

        Instant created = createdAt != null ? Instant.ofEpochMilli(createdAt) : Instant.now();
        Room room = new Room(id, name, deckEnum, created);
        room.setCustomCards(customCards != null ? customCards : List.of());
        room.setCurrentStory(currentStory);
        // Не восстанавливаем revealed и timer — новый раунд начнётся чистым
        room.setFinalEstimate(revealed ? finalEstimate : null);
        room.setTimerSeconds(timerSeconds);
        room.setActiveItemId(activeItemId);
        room.setOwnerUserId(ownerUserId);  // null для старых снимков без ownerUserId
        room.setAsync(async);

        if (participants != null) {
            for (ParticipantSnap ps : participants) {
                Participant.Role role;
                try { role = Participant.Role.valueOf(ps.role()); } catch (Exception e) { role = Participant.Role.PLAYER; }
                Participant p = new Participant(ps.id(), ps.name(), role);
                p.setOnline(false);           // все офлайн до реконнекта
                p.setVote(revealed ? ps.vote() : null);
                room.addParticipant(p);
            }
        }

        if (backlog != null) {
            for (BacklogSnap bs : backlog) {
                BacklogItem item = new BacklogItem(bs.id(), bs.title());
                item.setEstimate(bs.estimate());
                item.setRevotes(bs.revotes()); // 0 для старых снимков
                if (bs.votes() != null) {
                    item.setVotes(bs.votes().stream()
                            .map(v -> new BacklogItem.RoundVote(v.name(), v.value()))
                            .collect(Collectors.toList()));
                }
                if (bs.liveVotes() != null) {
                    bs.liveVotes().forEach(item::putLiveVote);
                }
                item.setRevealed(bs.itemRevealed());
                room.getBacklog().add(item);
            }
        }

        return room;
    }
}
