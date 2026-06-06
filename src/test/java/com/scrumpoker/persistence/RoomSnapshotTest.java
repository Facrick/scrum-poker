package com.scrumpoker.persistence;

import com.scrumpoker.model.BacklogItem;
import com.scrumpoker.model.Deck;
import com.scrumpoker.model.Participant;
import com.scrumpoker.model.Room;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class RoomSnapshotTest {

    @Test
    void roundTripPreservesCoreState() {
        Room room = new Room("abc12345", "Sprint 42", Deck.POWERS_OF_TWO);
        room.setCurrentStory("Login flow");
        Participant p = new Participant("p1", "Alice", Participant.Role.MODERATOR);
        p.setVote("8");
        room.addParticipant(p);
        BacklogItem item = new BacklogItem("id1", "Task A");
        item.setEstimate("5");
        room.getBacklog().add(item);
        room.setActiveItemId("id1");
        room.setRevealed(true);
        room.setFinalEstimate("8");

        Room restored = RoomSnapshot.from(room).toRoom();

        assertThat(restored.getId()).isEqualTo("abc12345");
        assertThat(restored.getName()).isEqualTo("Sprint 42");
        assertThat(restored.getDeck()).isEqualTo(Deck.POWERS_OF_TWO);
        assertThat(restored.getCurrentStory()).isEqualTo("Login flow");
        assertThat(restored.getActiveItemId()).isEqualTo("id1");
        assertThat(restored.getBacklog()).hasSize(1);
        assertThat(restored.getBacklog().get(0).getEstimate()).isEqualTo("5");
        assertThat(restored.getParticipants()).hasSize(1);

        Participant rp = restored.getParticipant("p1");
        assertThat(rp.getName()).isEqualTo("Alice");
        assertThat(rp.getRole()).isEqualTo(Participant.Role.MODERATOR);
        assertThat(rp.isOnline()).as("восстановленные участники офлайн до реконнекта").isFalse();
    }

    @Test
    void nullCreatedAtDoesNotThrow() {
        RoomSnapshot snap = new RoomSnapshot(
                "abc12345", "Room", "FIBONACCI", null, "", false, null, 0,
                null,                       // createdAt == null (старый/битый снимок)
                java.util.List.of(), java.util.List.of(), null);
        assertThatCode(snap::toRoom).doesNotThrowAnyException();
        assertThat(snap.toRoom().getCreatedAt()).isNotNull();
    }
}
