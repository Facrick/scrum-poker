package com.scrumpoker.account;

import java.time.Instant;

public record SessionHistory(
        String roomId,
        String ownerUserId,
        String roomName,
        int participantCount,
        int taskCount,
        int estimatedCount,
        Instant startedAt,
        Instant lastActiveAt
) {}
