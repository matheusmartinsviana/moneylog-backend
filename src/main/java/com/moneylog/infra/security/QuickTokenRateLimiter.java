package com.moneylog.infra.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public class QuickTokenRateLimiter {

    public record Settings(int maxFailuresPerIp, Duration failureWindow, int maxCallsPerToken, Duration callWindow, int maxEntries) {
    }

    private static final Duration PURGE_INTERVAL = Duration.ofMinutes(1);

    private final Clock clock;
    private final Settings settings;
    private final Map<String, Deque<Instant>> failures = new LinkedHashMap<>();
    private final Map<UUID, Deque<Instant>> calls = new LinkedHashMap<>();
    private Instant nextPurge = Instant.MIN;

    public QuickTokenRateLimiter(Clock clock, Settings settings) {
        this.clock = clock;
        this.settings = settings;
    }

    public synchronized void ensureIpAllowed(String ip) {
        Instant now = clock.instant();
        purgeIfDue(now);
        Deque<Instant> attempts = failures.get(LoginRateLimiter.ipKey(ip));
        if (attempts == null) {
            return;
        }
        prune(attempts, now, settings.failureWindow());
        if (attempts.size() >= settings.maxFailuresPerIp()) {
            throw new TooManyAttemptsException(wait(now, attempts.peekFirst(), settings.failureWindow()));
        }
    }

    public synchronized boolean recordFailure(String ip) {
        Instant now = clock.instant();
        String key = LoginRateLimiter.ipKey(ip);
        Deque<Instant> attempts = failures.get(key);
        if (attempts == null) {
            makeRoom(failures, now);
            attempts = new ArrayDeque<>();
            failures.put(key, attempts);
        }
        prune(attempts, now, settings.failureWindow());
        attempts.addLast(now);
        return attempts.size() == settings.maxFailuresPerIp();
    }

    public synchronized void acquireCall(UUID tokenId) {
        Instant now = clock.instant();
        purgeIfDue(now);
        Deque<Instant> window = calls.get(tokenId);
        if (window == null) {
            makeRoom(calls, now);
            window = new ArrayDeque<>();
            calls.put(tokenId, window);
        }
        prune(window, now, settings.callWindow());
        if (window.size() >= settings.maxCallsPerToken()) {
            throw new TooManyAttemptsException(wait(now, window.peekFirst(), settings.callWindow()));
        }
        window.addLast(now);
    }

    synchronized int trackedEntries() {
        return failures.size() + calls.size();
    }

    private void purgeIfDue(Instant now) {
        if (now.isBefore(nextPurge)) {
            return;
        }
        nextPurge = now.plus(PURGE_INTERVAL);
        purgeExpired(now);
    }

    private void purgeExpired(Instant now) {
        failures.values().removeIf(attempts -> {
            prune(attempts, now, settings.failureWindow());
            return attempts.isEmpty();
        });
        calls.values().removeIf(window -> {
            prune(window, now, settings.callWindow());
            return window.isEmpty();
        });
    }

    private <K> void makeRoom(Map<K, Deque<Instant>> map, Instant now) {
        if (map.size() < settings.maxEntries()) {
            return;
        }
        purgeExpired(now);
        if (map.size() < settings.maxEntries()) {
            return;
        }
        Iterator<K> eldest = map.keySet().iterator();
        eldest.next();
        eldest.remove();
    }

    private static void prune(Deque<Instant> attempts, Instant now, Duration window) {
        Instant limit = now.minus(window);
        while (!attempts.isEmpty() && !attempts.peekFirst().isAfter(limit)) {
            attempts.pollFirst();
        }
    }

    private static Duration wait(Instant now, Instant oldest, Duration window) {
        Duration wait = Duration.between(now, oldest.plus(window));
        return wait.isNegative() ? Duration.ZERO : wait;
    }
}
