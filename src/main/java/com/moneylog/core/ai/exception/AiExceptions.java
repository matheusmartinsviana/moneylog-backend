package com.moneylog.core.ai.exception;

import java.util.UUID;

import com.moneylog.core.sk.exception.BusinessRuleException;
import com.moneylog.core.sk.exception.DomainException;
import com.moneylog.core.sk.exception.NotFoundException;

/** Exceções da IA. Mensagens genéricas: nunca ecoam corpo de provedor, chave nem payload. */
public final class AiExceptions {

    private AiExceptions() {
    }

    public static class AiDisabledException extends BusinessRuleException {
        public AiDisabledException() {
            super("AI_DISABLED", "A IA não está configurada neste servidor. O moneylog funciona normalmente sem ela.");
        }
    }

    public static class AiBudgetExceededException extends BusinessRuleException {
        public AiBudgetExceededException() {
            super("AI_BUDGET_EXCEEDED", "O orçamento mensal de tokens de IA foi atingido.");
        }
    }

    public static class AiPreviewExpiredException extends BusinessRuleException {
        public AiPreviewExpiredException() {
            super("AI_PREVIEW_EXPIRED", "A prévia expirou. Gere uma nova antes de enviar.");
        }
    }

    public static class AiPreviewMismatchException extends BusinessRuleException {
        public AiPreviewMismatchException() {
            super("AI_PREVIEW_MISMATCH", "O conteúdo da prévia não confere com o aprovado.");
        }
    }

    public static class AiPreviewAlreadyUsedException extends BusinessRuleException {
        public AiPreviewAlreadyUsedException() {
            super("AI_PREVIEW_ALREADY_USED", "Esta prévia já foi usada. Gere uma nova.");
        }
    }

    public static class AiPurposeNotSupportedException extends BusinessRuleException {
        public AiPurposeNotSupportedException(String purpose) {
            super("AI_PURPOSE_NOT_SUPPORTED", "A finalidade " + purpose + " ainda não está disponível.");
        }
    }

    public static class AiPreviewNotFoundException extends NotFoundException {
        public AiPreviewNotFoundException(UUID id) {
            super("AI_PREVIEW_NOT_FOUND", "Prévia não encontrada: " + id);
        }
    }

    public static class AiAnalysisNotFoundException extends NotFoundException {
        public AiAnalysisNotFoundException(UUID id) {
            super("AI_ANALYSIS_NOT_FOUND", "Análise não encontrada: " + id);
        }
    }

    /** 429: limite diário de chamadas. Não é regra de negócio (422) nem invariante (500): o handler mapeia esta classe. */
    public static class AiRateLimitedException extends DomainException {
        public AiRateLimitedException() {
            super("AI_RATE_LIMITED", "Limite diário de chamadas de IA atingido. Tente amanhã.");
        }
    }

    /** 503: provedor indisponível (falhas seguidas com o disjuntor aberto, autenticação recusada, timeout). Mensagem sempre genérica. */
    public static class AiProviderUnavailableException extends DomainException {
        public AiProviderUnavailableException() {
            super("AI_PROVIDER_UNAVAILABLE", "O provedor de IA está indisponível no momento.");
        }
    }

    /** A saída do modelo foi descartada (fora do esquema ou com número que não está nos fatos). */
    public static class AiResponseRejectedException extends BusinessRuleException {
        public AiResponseRejectedException() {
            super("AI_RESPONSE_REJECTED", "A resposta da IA foi descartada por não passar na validação.");
        }
    }
}
