package com.scrumpoker.account;

public record User(
        String id,
        String provider,
        String providerId,
        String email,
        String displayName,
        String avatarUrl
) {}
