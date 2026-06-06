package com.scrumpoker.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimiterTest {

    @Test
    void allowsUpToLimitThenBlocks() {
        RateLimiter rl = new RateLimiter();
        for (int i = 0; i < 5; i++) {
            assertThat(rl.allow("k", 5, 10_000)).as("request %d", i).isTrue();
        }
        assertThat(rl.allow("k", 5, 10_000)).as("6th request blocked").isFalse();
    }

    @Test
    void keysAreIndependent() {
        RateLimiter rl = new RateLimiter();
        assertThat(rl.allow("a", 1, 10_000)).isTrue();
        assertThat(rl.allow("a", 1, 10_000)).isFalse();
        // другой ключ не затронут
        assertThat(rl.allow("b", 1, 10_000)).isTrue();
    }

    @Test
    void windowResetsAfterExpiry() {
        RateLimiter rl = new RateLimiter();
        assertThat(rl.allow("k", 1, 0)).isTrue();   // окно 0мс — мгновенно истекает
        assertThat(rl.allow("k", 1, 0)).isTrue();   // новое окно
    }

    @Test
    void evictRemovesStaleWindows() {
        RateLimiter rl = new RateLimiter();
        rl.allow("k", 1, 10_000);
        rl.evictOlderThan(0);                       // всё старше 0мс удаляется
        assertThat(rl.allow("k", 1, 10_000)).isTrue();
    }
}
