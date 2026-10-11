package com.moneylog.core.ai.app;

public interface AIService {

    boolean enabled();

    String provider();

    String model();

    AiResult analyzeStatement(AiRequest request);

    AiResult categorizeTransaction(AiRequest request);

    AiResult generateFinancialSummary(AiRequest request);

    AiResult generateInsights(AiRequest request);

    AiResult generateForecast(AiRequest request);

    AiResult generateDailyMessage(AiRequest request);
}
