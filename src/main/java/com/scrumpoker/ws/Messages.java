package com.scrumpoker.ws;

/** Входящие WebSocket-сообщения от клиента. */
public final class Messages {
    private Messages() {}

    /**
     * existingId — сохранённый participantId клиента, для восстановления сессии после реконнекта.
     * token — необязательный JWT владельца ЛК; если он валиден и принадлежит владельцу комнаты,
     * участник входит ведущим. Анонимные участники token не передают.
     */
    public record JoinMessage(String name, String role, String existingId, String token) {}
    public record VoteMessage(String participantId, String value) {}
    public record ModeratorAction(String participantId) {} // отправитель, для проверки прав
    public record SetStoryMessage(String participantId, String story) {}
    public record SetDeckMessage(String participantId, String deck) {}
    public record SetCustomDeckMessage(String participantId, java.util.List<String> cards) {}
    public record SetFinalEstimateMessage(String participantId, String estimate) {}
    public record KickMessage(String participantId, String targetId) {}
    public record PromoteMessage(String participantId, String targetId) {}
    // Таймер
    public record StartTimerMessage(String participantId, int seconds) {}
    public record StopTimerMessage(String participantId) {}
    // Бэклог
    public record AddBacklogItemMessage(String participantId, String title) {}
    public record RemoveBacklogItemMessage(String participantId, String itemId) {}
    public record ActivateBacklogItemMessage(String participantId, String itemId) {}
    public record ImportBacklogMessage(String participantId, java.util.List<String> titles) {}

    // Async-оценка (#3)
    public record AsyncModeMessage(String participantId, boolean enabled) {}
    public record AsyncVoteMessage(String participantId, String itemId, String value) {}
    public record ItemEstimateMessage(String participantId, String itemId, String value) {}
}
