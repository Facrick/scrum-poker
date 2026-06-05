package com.scrumpoker.ws;

/** Входящие WebSocket-сообщения от клиента. */
public final class Messages {
    private Messages() {}

    public record JoinMessage(String name, String role) {}
    public record VoteMessage(String participantId, String value) {}
    public record ModeratorAction(String participantId) {} // отправитель, для проверки прав
    public record SetStoryMessage(String participantId, String story) {}
    public record SetDeckMessage(String participantId, String deck) {}
    public record SetCustomDeckMessage(String participantId, java.util.List<String> cards) {}
    public record SetFinalEstimateMessage(String participantId, String estimate) {}
    public record KickMessage(String participantId, String targetId) {}
}
