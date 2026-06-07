package com.scrumpoker.model;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class BacklogItem {
    private final String id;
    private volatile String title;
    private volatile String estimate; // null = не оценено

    // История раунда по задаче (#6): сколько раз переголосовывали и кто как
    // проголосовал на момент фиксации оценки.
    private volatile int revotes;
    private volatile List<RoundVote> votes = List.of();

    // Async-оценка (#3): живые голоса по этой задаче (participantId -> значение)
    // и флаг «вскрыта» (голоса видны всем). В обычном режиме не используются.
    private final Map<String, String> liveVotes = new ConcurrentHashMap<>();
    private volatile boolean revealed;

    /** Голос одного участника в зафиксированном раунде. */
    public record RoundVote(String name, String value) {}

    public BacklogItem(String title) {
        this.id = UUID.randomUUID().toString();
        this.title = title;
    }

    /** Конструктор для восстановления из БД с существующим id. */
    public BacklogItem(String id, String title) {
        this.id = id;
        this.title = title;
    }

    public String getId() { return id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getEstimate() { return estimate; }
    public void setEstimate(String estimate) { this.estimate = estimate; }

    public int getRevotes() { return revotes; }
    public void setRevotes(int revotes) { this.revotes = revotes; }
    public void incrementRevotes() { this.revotes++; }

    public List<RoundVote> getVotes() { return votes; }
    public void setVotes(List<RoundVote> votes) { this.votes = votes == null ? List.of() : votes; }

    public Map<String, String> getLiveVotes() { return liveVotes; }
    public void putLiveVote(String participantId, String value) { liveVotes.put(participantId, value); }
    public boolean isRevealed() { return revealed; }
    public void setRevealed(boolean revealed) { this.revealed = revealed; }
}
