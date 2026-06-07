package com.scrumpoker.dto;

import com.scrumpoker.model.Participant;
import com.scrumpoker.model.Room;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Снимок состояния комнаты для рассылки клиентам. Значения голосов отдаются только после вскрытия. */
public record RoomStateDto(
        String roomId,
        String roomName,
        String deck,
        List<String> cards,
        String currentStory,
        boolean revealed,
        List<ParticipantView> participants,
        StatsDto stats,
        String finalEstimate,
        Long timerStartedAt,   // epoch millis, null = таймер не запущен
        int timerSeconds,
        List<BacklogItemView> backlog,
        String activeItemId
) {
    public record ParticipantView(String id, String name, String role, boolean online,
                                   boolean hasVoted, String vote) {}
    public record BacklogItemView(String id, String title, String estimate,
                                  int revotes, List<VoteView> votes) {}
    public record VoteView(String name, String value) {}

    public static RoomStateDto from(Room room) {
        boolean revealed = room.isRevealed();
        List<ParticipantView> views = room.getParticipants().stream()
                .map(p -> new ParticipantView(
                        p.getId(),
                        p.getName(),
                        p.getRole().name(),
                        p.isOnline(),
                        p.getVote() != null,
                        revealed ? p.getVote() : null))
                .sorted((a, b) -> a.name().compareToIgnoreCase(b.name()))
                .collect(Collectors.toList());

        StatsDto stats = revealed ? StatsDto.compute(room) : null;

        Long timerStartedAt = room.getTimerStartedAt() != null
                ? room.getTimerStartedAt().toEpochMilli() : null;

        List<BacklogItemView> backlog = room.getBacklog().stream()
                .map(i -> new BacklogItemView(i.getId(), i.getTitle(), i.getEstimate(),
                        i.getRevotes(),
                        i.getVotes().stream()
                                .map(v -> new VoteView(v.name(), v.value()))
                                .collect(Collectors.toList())))
                .collect(Collectors.toList());

        return new RoomStateDto(
                room.getId(),
                room.getName(),
                room.getDeck().name(),
                room.getEffectiveCards(),
                room.getCurrentStory(),
                revealed,
                views,
                stats,
                room.getFinalEstimate(),
                timerStartedAt,
                room.getTimerSeconds(),
                backlog,
                room.getActiveItemId());
    }

    /** Статистика раунда: среднее, медиана, распределение, наличие консенсуса. */
    public record StatsDto(Double average, Double median, boolean consensus,
                           Map<String, Long> distribution) {
        static StatsDto compute(Room room) {
            // В статистику входят все голосующие (модератор и участники), кроме наблюдателей.
            List<Double> numeric = room.getParticipants().stream()
                    .filter(p -> p.getRole() != Participant.Role.OBSERVER)
                    .map(Participant::getVote)
                    .filter(v -> v != null)
                    .map(StatsDto::parseNumeric)
                    .filter(v -> v != null)
                    .sorted()
                    .toList();

            Map<String, Long> distribution = room.getParticipants().stream()
                    .filter(p -> p.getRole() != Participant.Role.OBSERVER && p.getVote() != null)
                    .collect(Collectors.groupingBy(Participant::getVote, Collectors.counting()));

            Double average = null;
            Double median = null;
            if (!numeric.isEmpty()) {
                average = numeric.stream().mapToDouble(Double::doubleValue).average().orElse(0);
                int n = numeric.size();
                median = n % 2 == 1 ? numeric.get(n / 2)
                        : (numeric.get(n / 2 - 1) + numeric.get(n / 2)) / 2.0;
            }
            boolean consensus = distribution.size() == 1 && !distribution.isEmpty();
            return new StatsDto(average, median, consensus, distribution);
        }

        private static Double parseNumeric(String v) {
            try {
                return Double.parseDouble(v);
            } catch (NumberFormatException e) {
                return null; // "?", "☕", T-shirt и т.п. в среднее не входят
            }
        }
    }
}
