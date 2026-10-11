package com.moneylog.core.ai.app;

public interface LlmClient {

    LlmResponse complete(LlmRequest request) throws LlmException;

    record LlmRequest(String system, String userContent, int maxTokens) {
    }

    record LlmResponse(String text, int inputTokens, int outputTokens) {
    }

    class LlmException extends Exception {
        private final boolean transientFailure;

        public LlmException(String message, boolean transientFailure) {
            super(message);
            this.transientFailure = transientFailure;
        }

        public boolean isTransient() {
            return transientFailure;
        }
    }
}
