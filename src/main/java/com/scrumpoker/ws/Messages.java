package com.scrumpoker.ws;

/** Входящие WebSocket-сообщения от клиента. */
public final class Messages {
    private Messages() {}

    /** existingId — сохранённый participantId клиента, для восстановления сессии после реконнекта. */
    public record JoinMessage(String name, String role, String existingId, String token) {}
    public record VoteMessage(String participantId, String value) {}
    public record ModeratorAction(String participantId) {} // отправитель, для проверки прав
    public record SetStoryMessage(String participantId, String story) {}
    public record SetDeckMessage(String participantId, String deck) {}
    public record SetCustomDeckMessage(String participantId, java.util.List<String> cards) {}
    public record SetFinalEstimateMessage(String participantId, String estimate) {}
    public record KickMessage(String participantId, String targetId) {}
    public record TransferMessage(String participantId, String targetId) {}
    public record RenameMessage(String participantId, String name) {}
    // Таймер
    public record StartTimerMessage(String participantId, int seconds) {}
    public record StopTimerMessage(String participantId) {}
    // Бэклог
    public record AddBacklogItemMessage(String participantId, String title) {}
    public record ImportBacklogMessage(String participantId, java.util.List<String> titles) {}
    public record RemoveBacklogItemMessage(String participantId, String itemId) {}
    public record ActivateBacklogItemMessage(String participantId, String itemId) {}
}
