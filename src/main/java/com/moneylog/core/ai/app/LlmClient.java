package com.moneylog.core.ai.app;

/**
 * Adaptador de um provedor de modelo (D2 §3.6). Trocar de provedor = nova implementação desta porta; nada fora de {@code infra.ai.llm} conhece o
 * provedor. Implementações nunca registram a chave, o corpo da requisição nem o corpo de erro.
 */
public interface LlmClient {

    LlmResponse complete(LlmRequest request) throws LlmException;

    /** @param system instrução de sistema; @param userContent o payload aprovado, verbatim (dado, não instrução) */
    record LlmRequest(String system, String userContent, int maxTokens) {
    }

    record LlmResponse(String text, int inputTokens, int outputTokens) {
    }

    /** Falha do provedor sem eco de corpo. {@code transientFailure}: vale uma nova tentativa (429/5xx/rede). */
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
