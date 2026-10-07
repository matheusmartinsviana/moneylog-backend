package com.moneylog.core.ai.app;

/**
 * Porta da IA (D2 §3.6, PR 00 §27). As seis operações recebem um pedido já aprovado e devolvem um {@link AiResult}; a IA <b>nunca</b> altera o
 * razão: só produz texto sobre fatos calculados pelo backend ou sugestões que passam por revisão. Sem provedor, {@link NoopAIService}.
 */
public interface AIService {

    /** Há provedor configurado (a chave nunca passa por aqui). */
    boolean enabled();

    /** Nome do provedor ({@code none} quando desligado). */
    String provider();

    /** Modelo configurado, ou vazio. */
    String model();

    AiResult analyzeStatement(AiRequest request);

    AiResult categorizeTransaction(AiRequest request);

    AiResult generateFinancialSummary(AiRequest request);

    AiResult generateInsights(AiRequest request);

    AiResult generateForecast(AiRequest request);

    AiResult generateDailyMessage(AiRequest request);
}
