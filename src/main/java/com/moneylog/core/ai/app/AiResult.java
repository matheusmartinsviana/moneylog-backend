package com.moneylog.core.ai.app;

import java.util.List;

/** Resultado de uma operação do {@link AIService}: nunca lança por falha do provedor (o app mostra o equivalente determinístico). */
public sealed interface AiResult {

    int inputTokens();

    int outputTokens();

    /** Texto aterrado (S-184), com as referências de fato e o aviso opcional ({@code caveat}, ex.: mês parcial). */
    record AiCompleted(String text, String caveat, List<String> factRefs, int inputTokens, int outputTokens) implements AiResult {
        public AiCompleted {
            factRefs = List.copyOf(factRefs);
        }
    }

    record AiUnavailable(Reason reason, int inputTokens, int outputTokens) implements AiResult {
        public AiUnavailable(Reason reason) {
            this(reason, 0, 0);
        }
    }

    enum Reason {
        /** Sem provedor configurado. */
        DISABLED,
        /** Falha de rede, timeout, autenticação recusada ou erro do provedor. */
        PROVIDER_ERROR,
        /** Fora do esquema esperado. */
        INVALID_RESPONSE,
        /** Texto com número ou referência que não está nos fatos. */
        UNGROUNDED
    }
}
