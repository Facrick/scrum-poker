package com.scrumpoker.model;

import java.util.UUID;

public class BacklogItem {
    private final String id;
    private volatile String title;
    private volatile String estimate; // null = не оценено

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
}
