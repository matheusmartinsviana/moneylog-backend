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

/**
 * Limite de taxa por usuário, em memória: no máximo {@code maxRequests} pedidos numa janela deslizante (como o do login,
 * {@code LoginRateLimiter}). Correto para uma instância; com mais de uma o contador precisa de armazenamento compartilhado (a mesma pendência do
 * deploy do login). Estourou: {@code 429 TOO_MANY_ATTEMPTS} com {@code Retry-After}. Cada rota com limite próprio tem a sua subclasse, para os
 * contadores não se misturarem (a exportação tem 6 por minuto; a busca, 120).
 */
public abstract class UserRateLimiter {

    /** A partir deste tamanho, a gravação varre as chaves cujos pedidos já saíram da janela. */
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

    /** Registra o pedido ou, se o usuário já fez {@code maxRequests} na janela, lança {@link TooManyAttemptsException}. */
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
