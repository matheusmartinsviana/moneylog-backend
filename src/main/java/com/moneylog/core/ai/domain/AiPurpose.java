package com.moneylog.core.ai.domain;

/**
 * Finalidades de IA (S-181). Só as marcadas {@code supported} têm montagem de fatos e prévia nesta versão; as demais respondem
 * {@code AI_PURPOSE_NOT_SUPPORTED}. Nenhuma finalidade envia notas do vault (S-191).
 */
public enum AiPurpose {
    CATEGORIZE_IMPORT(false),
    CATEGORIZE_TRANSACTIONS(false),
    ANALYZE_STATEMENT(false),
    MONTHLY_SUMMARY(true),
    INSIGHTS(true),
    FORECAST(false),
    DAILY_MESSAGE(false);

    private final boolean supported;

    AiPurpose(boolean supported) {
        this.supported = supported;
    }

    public boolean supported() {
        return supported;
    }
}
