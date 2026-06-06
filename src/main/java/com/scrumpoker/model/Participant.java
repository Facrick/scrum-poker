package com.scrumpoker.model;

public class Participant {
    private final String id;
    private volatile String name;
    private volatile Role role;
    private volatile String vote;     // null = не голосовал в текущем раунде
    private volatile boolean online = true;
    // Текущая WS-сессия владельца. Используется для авторизации действий и
    // корректной обработки реконнектов (старый disconnect не должен гасить новую сессию).
    private volatile String sessionId;

    public Participant(String id, String name, Role role) {
        this.id = id;
        this.name = name;
        this.role = role;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Role getRole() { return role; }
    public void setRole(Role role) { this.role = role; }
    public String getVote() { return vote; }
    public void setVote(String vote) { this.vote = vote; }
    public boolean isOnline() { return online; }
    public void setOnline(boolean online) { this.online = online; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public enum Role { MODERATOR, PLAYER, OBSERVER }
}
