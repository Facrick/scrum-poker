package com.scrumpoker.model;

import java.util.List;

public enum Deck {
    FIBONACCI(List.of("0", "1", "2", "3", "5", "8", "13", "21", "?", "☕")),
    TSHIRT(List.of("XS", "S", "M", "L", "XL", "?", "☕")),
    POWERS_OF_TWO(List.of("1", "2", "4", "8", "16", "32", "?", "☕")),
    /** Кастомная колода — реальные карты хранятся в Room.customCards. */
    CUSTOM(List.of());

    private final List<String> cards;

    Deck(List<String> cards) {
        this.cards = cards;
    }

    public List<String> getCards() {
        return cards;
    }
}
