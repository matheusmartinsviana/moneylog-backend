package com.moneylog.infra.ai.config;

import java.util.Locale;
import java.util.Set;

/**
 * Configuração de IA vinda do ambiente (S-185). A chave nunca sai desta classe: {@link #toString()} a mascara, e nenhuma mensagem de erro de
 * validação a inclui (nem parte dela). {@code none}/{@code noop} (padrão) desliga a IA; {@code fake} só existe fora de produção.
 */
public final class AiProperties {

    public static final String NONE = "none";
    public static final String ANTHROPIC = "anthropic";
    public static final String FAKE = "fake";
    private static final Set<String> KNOWN = Set.of(NONE, ANTHROPIC, FAKE);

    private final String provider;
    private final String apiKey;
    private final String model;
    private final String baseUrl;
    private final int timeoutSeconds;
    private final int dailyCallLimit;
    private final long monthlyTokenBudget;
    private final int payloadRetentionDays;

    public AiProperties(String provider, String apiKey, String model, String baseUrl, int timeoutSeconds, int dailyCallLimit,
                        long monthlyTokenBudget, int payloadRetentionDays) {
        String normalized = provider == null || provider.isBlank() || provider.strip().equalsIgnoreCase("noop")
                ? NONE : provider.strip().toLowerCase(Locale.ROOT);
        if (!KNOWN.contains(normalized)) {
            throw new IllegalStateException("AI_PROVIDER inválido. Use none, anthropic ou fake (veja .env.example).");
        }
        this.provider = normalized;
        this.apiKey = apiKey == null ? "" : apiKey.strip();
        this.model = model == null ? "" : model.strip();
        this.baseUrl = baseUrl == null ? "" : baseUrl.strip();
        this.timeoutSeconds = Math.max(1, timeoutSeconds);
        this.dailyCallLimit = Math.max(0, dailyCallLimit);
        this.monthlyTokenBudget = Math.max(0, monthlyTokenBudget);
        this.payloadRetentionDays = Math.max(1, payloadRetentionDays);
        if (ANTHROPIC.equals(normalized) && (this.apiKey.isEmpty() || this.model.isEmpty())) {
            throw new IllegalStateException("AI_PROVIDER=anthropic exige AI_API_KEY e AI_MODEL no ambiente do servidor (veja .env.example). "
                    + "Sem eles, use AI_PROVIDER=none: o app funciona normalmente sem IA.");
        }
    }

    public boolean enabled() {
        return !NONE.equals(provider);
    }

    public String provider() {
        return provider;
    }

    /** Uso exclusivo do adaptador do provedor (cabeçalho de autenticação). */
    public String apiKey() {
        return apiKey;
    }

    public String model() {
        return model;
    }

    public String baseUrl() {
        return baseUrl;
    }

    public int timeoutSeconds() {
        return timeoutSeconds;
    }

    public int dailyCallLimit() {
        return dailyCallLimit;
    }

    public long monthlyTokenBudget() {
        return monthlyTokenBudget;
    }

    public int payloadRetentionDays() {
        return payloadRetentionDays;
    }

    @Override
    public String toString() {
        return "AiProperties[provider=" + provider + ", apiKey=" + (apiKey.isEmpty() ? "" : "****") + ", model=" + model + ", timeoutSeconds="
                + timeoutSeconds + ", dailyCallLimit=" + dailyCallLimit + ", monthlyTokenBudget=" + monthlyTokenBudget + "]";
    }
}
