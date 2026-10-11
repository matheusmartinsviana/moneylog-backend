package com.moneylog.infra.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class LoginRateLimiter {

    private final Clock clock;
    private final int maxFailures;
    private final Duration window;
    private final Map<String, Deque<Instant>> failures = new ConcurrentHashMap<>();

    public LoginRateLimiter(Clock clock, int maxFailures, Duration window) {
        this.clock = clock;
        this.maxFailures = maxFailures;
        this.window = window;
    }

    public void ensureNotBlocked(String ip) {
        Deque<Instant> attempts = failures.get(ip);
        if (attempts == null) {
            return;
        }
        synchronized (attempts) {
            Instant now = clock.instant();
            prune(attempts, now);
            if (attempts.size() >= maxFailures) {
                Duration wait = Duration.between(now, attempts.peekFirst().plus(window));
                throw new TooManyAttemptsException(wait.isNegative() ? Duration.ZERO : wait);
            }
        }
    }

    private static final int PRUNE_THRESHOLD = 1_000;

    public void recordFailure(String ip) {
        if (failures.size() > PRUNE_THRESHOLD) {
            pruneKeys();
        }
        Deque<Instant> attempts = failures.computeIfAbsent(ip, key -> new ArrayDeque<>());
        synchronized (attempts) {
            Instant now = clock.instant();
            prune(attempts, now);
            attempts.addLast(now);
        }
    }

    public void reset(String ip) {
        failures.remove(ip);
    }

    private void pruneKeys() {
        Instant now = clock.instant();
        failures.entrySet().removeIf(entry -> {
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
