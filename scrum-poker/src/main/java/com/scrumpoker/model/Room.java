package com.scrumpoker.model;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class Room {
    private final String id;
    private volatile String name;
    private volatile Deck deck;
    private volatile String currentStory = "";
    private volatile boolean revealed = false;
    private volatile List<String> customCards = List.of();
    private volatile String finalEstimate = null;
    private final Instant createdAt = Instant.now();
    private final Map<String, Participant> participants = new ConcurrentHashMap<>();

    public Room(String id, String name, Deck deck) {
        this.id = id;
        this.name = name;
        this.deck = deck;
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
    public boolean isRevealed() { return revealed; }
    public void setRevealed(boolean revealed) { this.revealed = revealed; }
    public Instant getCreatedAt() { return createdAt; }

    public Collection<Participant> getParticipants() { return participants.values(); }
    public Participant getParticipant(String id) { return participants.get(id); }
    public void addParticipant(Participant p) { participants.put(p.getId(), p); }
    public Participant removeParticipant(String id) { return participants.remove(id); }
    public int size() { return participants.size(); }

    /** Сбросить раунд: спрятать карты и очистить голоса. */
    public void resetRound() {
        revealed = false;
        participants.values().forEach(p -> p.setVote(null));
    }
}
