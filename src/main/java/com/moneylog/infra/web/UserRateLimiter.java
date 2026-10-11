package com.moneylog.infra.web;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.moneylog.infra.security.TooManyAttemptsException;

public abstract class UserRateLimiter {

    private static final int PRUNE_THRESHOLD = 1_000;

    private final Clock clock;
    private final int maxRequests;
    private final Duration window;
    private final Map<UUID, Deque<Instant>> requests = new ConcurrentHashMap<>();

    protected UserRateLimiter(Clock clock, int maxRequests, Duration window) {
        this.clock = clock;
        this.maxRequests = maxRequests;
        this.window = window;
    }

    public void acquire(UUID userId) {
        if (requests.size() > PRUNE_THRESHOLD) {
            pruneKeys();
        }
        Deque<Instant> attempts = requests.computeIfAbsent(userId, key -> new ArrayDeque<>());
        synchronized (attempts) {
            Instant now = clock.instant();
            prune(attempts, now);
            if (attempts.size() >= maxRequests) {
                Duration wait = Duration.between(now, attempts.peekFirst().plus(window));
                throw new TooManyAttemptsException(wait.isNegative() ? Duration.ZERO : wait);
            }
            attempts.addLast(now);
        }
    }

    private void pruneKeys() {
        Instant now = clock.instant();
        requests.entrySet().removeIf(entry -> {
            synchronized (entry.getValue()) {
                prune(entry.getValue(), now);
                return entry.getValue().isEmpty();
            }
        });
    }

    private void prune(Deque<Instant> attempts, Instant now) {
        Instant limit = now.minus(window);
        while (!attempts.isEmpty() && !attempts.peekFirst().isAfter(limit)) {
            attempts.pollFirst();
        }
    }
}
