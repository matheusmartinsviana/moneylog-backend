package com.moneylog.infra.security;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

import com.moneylog.infra.security.TooManyAttemptsException.Scope;

public class LoginRateLimiter {

    public record Attempt(String ipKey, String accountKey) {
    }

    public record Settings(int maxIpFailures, Duration window, int maxAccountFailures, Duration accountLockBase,
                           Duration accountLockMax, Duration trustedIpTtl, int maxEntries) {

        public static Settings of(int maxIpFailures, Duration window) {
            return new Settings(maxIpFailures, window, maxIpFailures * 2, Duration.ofMinutes(1), window, Duration.ofDays(30), 10_000);
        }
    }

    private static final Duration PURGE_INTERVAL = Duration.ofMinutes(1);
    private static final int MAX_TRUSTED_IPS = 1_000;
    private static final int MAX_KEY_LENGTH = 200;

    private static final class Bucket {
        final Deque<Instant> attempts = new ArrayDeque<>();
        Instant blockedUntil;
        int strikes;
    }

    private final Clock clock;
    private final Settings settings;
    private final Map<String, Bucket> ips = new LinkedHashMap<>();
    private final Map<String, Bucket> accounts = new LinkedHashMap<>();
    private final Map<String, Instant> trustedIps = new LinkedHashMap<>();
    private Instant nextPurge = Instant.MIN;

    public LoginRateLimiter(Clock clock, int maxFailures, Duration window) {
        this(clock, Settings.of(maxFailures, window));
    }

    public LoginRateLimiter(Clock clock, Settings settings) {
        this.clock = clock;
        this.settings = settings;
    }

    public synchronized Attempt reserve(String ip, String account) {
        Instant now = clock.instant();
        purgeIfDue(now);
        String ipKey = ipKey(ip);
        String accountKey = accountKey(account);

        Bucket ipBucket = ips.get(ipKey);
        if (ipBucket != null) {
            prune(ipBucket.attempts, now);
            if (ipBucket.attempts.size() >= settings.maxIpFailures()) {
                Duration wait = Duration.between(now, ipBucket.attempts.peekFirst().plus(settings.window()));
                throw new TooManyAttemptsException(nonNegative(wait), Scope.IP);
            }
        }
        Bucket accountBucket = accounts.get(accountKey);
        if (accountBucket != null) {
            decayStrikes(accountBucket, now);
            boolean known = isTrusted(ipKey, now);
            if (!known && accountBucket.blockedUntil != null && now.isBefore(accountBucket.blockedUntil)) {
                throw new TooManyAttemptsException(nonNegative(Duration.between(now, accountBucket.blockedUntil)), Scope.ACCOUNT);
            }
        }

        if (ipBucket == null) {
            makeRoom(ips, now, bucket -> bucket.attempts.size() >= settings.maxIpFailures());
            ipBucket = new Bucket();
            ips.put(ipKey, ipBucket);
        }
        ipBucket.attempts.addLast(now);

        if (accountBucket == null) {
            makeRoom(accounts, now, bucket -> bucket.blockedUntil != null && now.isBefore(bucket.blockedUntil));
            accountBucket = new Bucket();
            accounts.put(accountKey, accountBucket);
        }
        prune(accountBucket.attempts, now);
        accountBucket.attempts.addLast(now);
        if (accountBucket.attempts.size() >= settings.maxAccountFailures()) {
            accountBucket.strikes++;
            accountBucket.blockedUntil = now.plus(lockFor(accountBucket.strikes));
            accountBucket.attempts.clear();
        }
        return new Attempt(ipKey, accountKey);
    }

    public synchronized void succeeded(Attempt attempt) {
        ips.remove(attempt.ipKey());
        accounts.remove(attempt.accountKey());
        Instant now = clock.instant();
        trustedIps.remove(attempt.ipKey());
        if (trustedIps.size() >= MAX_TRUSTED_IPS) {
            Iterator<String> eldest = trustedIps.keySet().iterator();
            eldest.next();
            eldest.remove();
        }
        trustedIps.put(attempt.ipKey(), now.plus(settings.trustedIpTtl()));
    }

    synchronized int trackedEntries() {
        return ips.size() + accounts.size() + trustedIps.size();
    }

    private Duration lockFor(int strikes) {
        long factor = 1L << Math.min(strikes - 1, 20);
        Duration lock = settings.accountLockBase().multipliedBy(factor);
        return lock.compareTo(settings.accountLockMax()) > 0 ? settings.accountLockMax() : lock;
    }

    private void decayStrikes(Bucket bucket, Instant now) {
        if (bucket.strikes > 0 && bucket.blockedUntil != null && now.isAfter(bucket.blockedUntil.plus(settings.window()))) {
            bucket.strikes = 0;
            bucket.blockedUntil = null;
        }
    }

    private boolean isTrusted(String ipKey, Instant now) {
        Instant expiresAt = trustedIps.get(ipKey);
        return expiresAt != null && now.isBefore(expiresAt);
    }

    private void purgeIfDue(Instant now) {
        if (now.isBefore(nextPurge)) {
            return;
        }
        nextPurge = now.plus(PURGE_INTERVAL);
        purgeExpired(now);
    }

    private void purgeExpired(Instant now) {
        ips.values().removeIf(bucket -> {
            prune(bucket.attempts, now);
            return bucket.attempts.isEmpty();
        });
        accounts.values().removeIf(bucket -> {
            prune(bucket.attempts, now);
            decayStrikes(bucket, now);
            boolean lockActive = bucket.blockedUntil != null && now.isBefore(bucket.blockedUntil.plus(settings.window()));
            return bucket.attempts.isEmpty() && !lockActive;
        });
        trustedIps.values().removeIf(expiresAt -> !now.isBefore(expiresAt));
    }

    private void makeRoom(Map<String, Bucket> map, Instant now, Predicate<Bucket> protectedBucket) {
        if (map.size() < settings.maxEntries()) {
            return;
        }
        purgeExpired(now);
        if (map.size() < settings.maxEntries()) {
            return;
        }
        Iterator<Map.Entry<String, Bucket>> iterator = map.entrySet().iterator();
        String victim = null;
        while (iterator.hasNext()) {
            Map.Entry<String, Bucket> entry = iterator.next();
            if (victim == null) {
                victim = entry.getKey();
            }
            if (!protectedBucket.test(entry.getValue())) {
                victim = entry.getKey();
                break;
            }
        }
        if (victim != null) {
            map.remove(victim);
        }
    }

    private void prune(Deque<Instant> attempts, Instant now) {
        Instant limit = now.minus(settings.window());
        while (!attempts.isEmpty() && !attempts.peekFirst().isAfter(limit)) {
            attempts.pollFirst();
        }
    }

    private static Duration nonNegative(Duration value) {
        return value.isNegative() ? Duration.ZERO : value;
    }

    static String accountKey(String account) {
        String value = account == null ? "" : account.strip().toLowerCase(Locale.ROOT);
        return value.length() > MAX_KEY_LENGTH ? value.substring(0, MAX_KEY_LENGTH) : value;
    }

    static String ipKey(String ip) {
        String value = ip == null ? "unknown" : ip.strip();
        if (value.indexOf(':') < 0 || !value.matches("[0-9a-fA-F:.]+")) {
            return value.length() > MAX_KEY_LENGTH ? value.substring(0, MAX_KEY_LENGTH) : value;
        }
        try {
            InetAddress address = InetAddress.getByName(value);
            byte[] bytes = address.getAddress();
            if (bytes.length == 16) {
                return HexFormat.of().formatHex(bytes, 0, 8) + "/64";
            }
            return address.getHostAddress();
        } catch (UnknownHostException e) {
            return value.length() > MAX_KEY_LENGTH ? value.substring(0, MAX_KEY_LENGTH) : value;
        }
    }
}
