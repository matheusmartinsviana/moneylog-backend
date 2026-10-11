package com.moneylog.infra.ai.controller;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.moneylog.core.ai.app.AiGuard;
import com.moneylog.core.ai.app.usecase.AiUseCases.StatusView;
import com.moneylog.core.ai.domain.AiAnalysis;
import com.moneylog.core.ai.domain.AiPreview;
import com.moneylog.core.ai.domain.AiPurpose;
import com.moneylog.core.ai.domain.AiSettings;
import com.moneylog.core.ai.domain.AiStatus;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public final class AiDtos {

    private AiDtos() {
    }

    public record StatusResponse(boolean enabled, String provider, String model, long dailyCallsRemaining, long monthlyTokensRemaining,
                                 AiGuard.Circuit circuit) {
        static StatusResponse of(StatusView v) {
            return new StatusResponse(v.enabled(), v.provider(), v.model(), v.dailyCallsRemaining(), v.monthlyTokensRemaining(), v.circuit());
        }
    }

    public record ScopeInput(@Pattern(regexp = "^\\d{4}-\\d{2}$") String month, @Pattern(regexp = "^(pt|en|es)$") String language) {
    }

    public record PreviewInput(@NotNull AiPurpose purpose, @Valid ScopeInput scope) {
    }

    public record PreviewResponse(UUID previewId, AiPurpose purpose, String period, String language, String redactedPayload, Map<String, Integer> counts,
                                  int estimatedTokens, Instant expiresAt, List<String> redactions) {
        static PreviewResponse of(AiPreview p) {
            return new PreviewResponse(p.id(), p.purpose(), p.period(), p.language(), p.payload(), p.counts(), p.estimatedTokens(), p.expiresAt(),
                    p.redactions());
        }
    }

    public record AnalysisInput(@NotNull UUID previewId, @NotNull Boolean approve) {
    }

    public record Tokens(int input, int output) {
    }

    public record Result(String text, String caveat, List<String> factRefs, String source) {
    }

    public record AnalysisResponse(UUID id, UUID previewId, AiPurpose purpose, String period, String provider, String model, AiStatus status,
                                   Tokens tokens, long durationMs, Result result, boolean fallback, String failureReason, String redactedPayload,
                                   Instant payloadPurgedAt, Instant createdAt) {
        static AnalysisResponse of(AiAnalysis a) {
            return new AnalysisResponse(a.id(), a.previewId(), a.purpose(), a.period(), a.provider(), a.model(), a.status(),
                    new Tokens(a.inputTokens(), a.outputTokens()), a.durationMs(), a.hasResult() ? resultOf(a) : null, !a.hasResult(), a.failureReason(),
                    a.redactedPayload(), a.payloadPurgedAt(), a.createdAt());
        }
    }

    public record AnalysisSummaryResponse(UUID id, AiPurpose purpose, String period, String provider, String model, AiStatus status, Tokens tokens,
                                          boolean fallback, boolean payloadAvailable, Instant payloadPurgedAt, Instant createdAt) {
        static AnalysisSummaryResponse of(AiAnalysis a) {
            return new AnalysisSummaryResponse(a.id(), a.purpose(), a.period(), a.provider(), a.model(), a.status(),
                    new Tokens(a.inputTokens(), a.outputTokens()), !a.hasResult(), a.redactedPayload() != null, a.payloadPurgedAt(), a.createdAt());
        }
    }

    private static Result resultOf(AiAnalysis a) {
        return new Result(a.resultText(), a.caveat(), a.factRefs(), "AI");
    }

    public record SettingsDto(@NotNull Boolean dailyMessageEnabled) {
        static SettingsDto of(AiSettings s) {
            return new SettingsDto(s.dailyMessageEnabled());
        }
    }
}
