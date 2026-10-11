package com.moneylog.core.ai.app;

import java.util.List;

public sealed interface AiResult {

    int inputTokens();

    int outputTokens();

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
        DISABLED,
        PROVIDER_ERROR,
        INVALID_RESPONSE,
        UNGROUNDED
    }
}
