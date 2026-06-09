package com.scrumpoker.model;

import java.util.List;
import java.util.UUID;

public class BacklogItem {

    public record VoteRecord(String name, String value) {}

    private final String id;
    private volatile String title;
    private volatile String estimate;           // null = не оценено
    private volatile int revotes = 0;           // сколько раз переголосовывали
    private volatile List<VoteRecord> votes = List.of(); // голоса последнего раунда

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
    public List<VoteRecord> getVotes() { return votes; }
    public void setVotes(List<VoteRecord> votes) { this.votes = votes == null ? List.of() : votes; }
}
