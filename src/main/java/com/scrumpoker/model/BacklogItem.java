package com.scrumpoker.model;

import java.util.UUID;

public class BacklogItem {
    private final String id = UUID.randomUUID().toString();
    private volatile String title;
    private volatile String estimate; // null = не оценено

    public BacklogItem(String title) {
        this.title = title;
    }

    public String getId() { return id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getEstimate() { return estimate; }
    public void setEstimate(String estimate) { this.estimate = estimate; }
}
