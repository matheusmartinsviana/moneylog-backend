package com.moneylog.core.ai.app;

import com.moneylog.core.ai.app.AiResult.AiUnavailable;
import com.moneylog.core.ai.app.AiResult.Reason;

/** Sem provedor (padrão): toda operação devolve "indisponível", nunca erro. O app inteiro funciona sem IA. */
public class NoopAIService implements AIService {

    private static final AiResult UNAVAILABLE = new AiUnavailable(Reason.DISABLED);

    @Override
    public boolean enabled() {
        return false;
    }

    @Override
    public String provider() {
        return "none";
    }

    @Override
    public String model() {
        return "";
    }

    @Override
    public AiResult analyzeStatement(AiRequest request) {
        return UNAVAILABLE;
    }

    @Override
    public AiResult categorizeTransaction(AiRequest request) {
        return UNAVAILABLE;
    }

    @Override
    public AiResult generateFinancialSummary(AiRequest request) {
        return UNAVAILABLE;
    }

    @Override
    public AiResult generateInsights(AiRequest request) {
        return UNAVAILABLE;
    }

    @Override
    public AiResult generateForecast(AiRequest request) {
        return UNAVAILABLE;
    }

    @Override
    public AiResult generateDailyMessage(AiRequest request) {
        return UNAVAILABLE;
    }
}
