package com.moneylog.core.ai.app;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

import com.moneylog.core.ai.app.gateways.AiAnalysisGateway;
import com.moneylog.core.ai.exception.AiExceptions.AiBudgetExceededException;
import com.moneylog.core.ai.exception.AiExceptions.AiProviderUnavailableException;
import com.moneylog.core.ai.exception.AiExceptions.AiRateLimitedException;
import com.moneylog.core.sk.time.TimeProvider;

/**
 * Custo e resiliência (S-186): limite diário de chamadas (dia em {@code America/Sao_Paulo}), orçamento mensal de tokens e disjuntor (5 falhas
 * seguidas do provedor abrem por 5 min; depois disso a próxima chamada é a sondagem: sucesso fecha, falha reabre). O consumo é lido das análises
 * registradas; chamadas em andamento entram na conta ({@code inFlight}), então 10 chamadas simultâneas no limite nunca o ultrapassam. Uma instância
 * só (ADR-001): o estado do disjuntor e do {@code inFlight} é em memória.
 */
public class AiGuard {

    public static final int FAILURES_TO_OPEN = 5;
    public static final Duration OPEN_FOR = Duration.ofMinutes(5);

    public record Limits(int dailyCallLimit, long monthlyTokenBudget) {
    }

    public enum Circuit { CLOSED, OPEN }

    public record Usage(long dailyCallsRemaining, long monthlyTokensRemaining, Circuit circuit) {
    }

    private final AiAnalysisGateway analyses;
    private final TimeProvider time;
    private final Limits limits;
    private final ReentrantLock lock = new ReentrantLock();
    private int inFlight;
    private int consecutiveFailures;
    private Instant openUntil;

    public AiGuard(AiAnalysisGateway analyses, TimeProvider time, Limits limits) {
        this.analyses = analyses;
        this.time = time;
        this.limits = limits;
    }

    /**
     * Reserva uma chamada. Ordem: disjuntor (503), limite diário (429), orçamento (422).
     * Quem recebe a reserva <b>deve</b> chamar {@link #release} (em {@code finally}).
     */
    public void acquire(UUID userId, int estimatedTokens) {
        lock.lock();
        try {
            if (circuitOpen()) {
                throw new AiProviderUnavailableException();
            }
            if (dailyCalls(userId) + inFlight >= limits.dailyCallLimit()) {
                throw new AiRateLimitedException();
            }
            if (monthlyTokens(userId) + estimatedTokens > limits.monthlyTokenBudget()) {
                throw new AiBudgetExceededException();
            }
            inFlight++;
        } finally {
            lock.unlock();
        }
    }

    /** @param providerHealthy {@code false} só para falha do provedor (rede, timeout, 401/403/5xx); resposta inválida não conta para o disjuntor */
    public void release(boolean providerHealthy) {
        lock.lock();
        try {
            inFlight = Math.max(0, inFlight - 1);
            if (providerHealthy) {
                consecutiveFailures = 0;
                openUntil = null;
            } else {
                consecutiveFailures++;
                if (consecutiveFailures >= FAILURES_TO_OPEN) {
                    openUntil = time.now().plus(OPEN_FOR);
                }
            }
        } finally {
            lock.unlock();
        }
    }

    public Usage usage(UUID userId) {
        lock.lock();
        try {
            return new Usage(Math.max(0, limits.dailyCallLimit() - dailyCalls(userId) - inFlight),
                    Math.max(0, limits.monthlyTokenBudget() - monthlyTokens(userId)), circuitOpen() ? Circuit.OPEN : Circuit.CLOSED);
        } finally {
            lock.unlock();
        }
    }

    private boolean circuitOpen() {
        return openUntil != null && time.now().isBefore(openUntil);
    }

    private long dailyCalls(UUID userId) {
        ZoneId zone = time.zone();
        LocalDate today = time.today();
        return analyses.countProviderCalls(userId, today.atStartOfDay(zone).toInstant(), today.plusDays(1).atStartOfDay(zone).toInstant());
    }

    private long monthlyTokens(UUID userId) {
        ZoneId zone = time.zone();
        LocalDate first = time.today().withDayOfMonth(1);
        return analyses.sumTokens(userId, first.atStartOfDay(zone).toInstant(), first.plusMonths(1).atStartOfDay(zone).toInstant());
    }
}
