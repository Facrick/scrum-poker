package com.scrumpoker.model;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class Room {
    private final String id;
    private volatile String name;
    private volatile Deck deck;
    private volatile String currentStory = "";
    private volatile boolean revealed = false;
    private volatile List<String> customCards = List.of();
    private volatile String finalEstimate = null;
    // Таймер
    private volatile int timerSeconds = 0;
    private volatile Instant timerStartedAt = null;
    // Бэклог задач
    private final List<BacklogItem> backlog = new CopyOnWriteArrayList<>();
    private volatile String activeItemId = null;
    private Instant createdAt = Instant.now();
    private volatile Instant lastActivityAt = Instant.now();
    private volatile String ownerUserId = null;
    private volatile String ownerParticipantId = null; // participantId владельца (если он в комнате)
    private final Map<String, Participant> participants = new ConcurrentHashMap<>();

    public Room(String id, String name, Deck deck) {
        this.id = id;
        this.name = name;
        this.deck = deck;
    }

    /** Конструктор для восстановления из БД с сохранением исходного времени создания. */
    public Room(String id, String name, Deck deck, Instant createdAt) {
        this.id = id;
        this.name = name;
        this.deck = deck;
        this.createdAt = createdAt;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Deck getDeck() { return deck; }
    public void setDeck(Deck deck) { this.deck = deck; }
    public List<String> getCustomCards() { return customCards; }
    public void setCustomCards(List<String> customCards) { this.customCards = customCards; }
    /** Возвращает реальный список карт: кастомные для CUSTOM, иначе из enum. */
    public List<String> getEffectiveCards() { return deck == Deck.CUSTOM ? customCards : deck.getCards(); }
    public String getFinalEstimate() { return finalEstimate; }
    public void setFinalEstimate(String finalEstimate) { this.finalEstimate = finalEstimate; }
    public String getCurrentStory() { return currentStory; }
    public void setCurrentStory(String story) { this.currentStory = story == null ? "" : story; this.finalEstimate = null; }

    public int getTimerSeconds() { return timerSeconds; }
    public void setTimerSeconds(int s) { this.timerSeconds = s; }
    public Instant getTimerStartedAt() { return timerStartedAt; }
    public void setTimerStartedAt(Instant t) { this.timerStartedAt = t; }

    public List<BacklogItem> getBacklog() { return backlog; }
    public String getActiveItemId() { return activeItemId; }
    public void setActiveItemId(String id) { this.activeItemId = id; }
    public boolean isRevealed() { return revealed; }
    public void setRevealed(boolean revealed) { this.revealed = revealed; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getLastActivityAt() { return lastActivityAt; }
    /** Отметить активность комнаты (вызывается при каждом broadcast). */
    public void touch() { this.lastActivityAt = Instant.now(); }
    public String getOwnerUserId() { return ownerUserId; }
    public void setOwnerUserId(String ownerUserId) { this.ownerUserId = ownerUserId; }
    public String getOwnerParticipantId() { return ownerParticipantId; }
    public void setOwnerParticipantId(String id) { this.ownerParticipantId = id; }

    public Collection<Participant> getParticipants() { return participants.values(); }
    public Participant getParticipant(String id) { return participants.get(id); }
    public void addParticipant(Participant p) { participants.put(p.getId(), p); }
    public Participant removeParticipant(String id) { return participants.remove(id); }
    public int size() { return participants.size(); }

    /** Сбросить раунд: спрятать карты, очистить голоса, итоговую оценку и остановить таймер. */
    public void resetRound() {
        revealed = false;
        timerStartedAt = null;
        finalEstimate = null;
        participants.values().forEach(p -> p.setVote(null));
    }
}
