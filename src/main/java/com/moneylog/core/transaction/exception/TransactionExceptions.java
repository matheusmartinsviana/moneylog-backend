package com.moneylog.core.transaction.exception;

import java.util.UUID;

import com.moneylog.core.sk.exception.BusinessRuleException;
import com.moneylog.core.sk.exception.ConflictException;
import com.moneylog.core.sk.exception.DomainException;
import com.moneylog.core.sk.exception.NotFoundException;
import com.moneylog.core.sk.ledger.VoidDecision;
import com.moneylog.core.transaction.domain.TransactionType;

/**
 * Exceções da feature movimentação.
 */
public final class TransactionExceptions {

    private TransactionExceptions() {
    }

    public static class TransactionNotFoundException extends NotFoundException {
        public TransactionNotFoundException(UUID id) {
            super("TRANSACTION_NOT_FOUND", "Movimentação não encontrada: " + id + ".");
        }
    }

    public static class TransactionFieldRequiredException extends BusinessRuleException {
        public TransactionFieldRequiredException(String field) {
            super("TRANSACTION_FIELD_REQUIRED", "Campo obrigatório da movimentação ausente: " + field + ".");
        }
    }

    public static class TransactionTextTooLongException extends BusinessRuleException {
        public TransactionTextTooLongException(String field, int max) {
            super("TRANSACTION_TEXT_TOO_LONG", "O campo " + field + " pode ter no máximo " + max + " caracteres.");
        }
    }

    public static class InvalidTransactionOriginException extends BusinessRuleException {
        public InvalidTransactionOriginException(String reason) {
            super("TRANSACTION_INVALID_ORIGIN", "Origem da movimentação inválida: " + reason);
        }
    }

    /** O valor da movimentação é sempre > 0; a direção vem do tipo. */
    public static class NonPositiveAmountException extends BusinessRuleException {
        public NonPositiveAmountException() {
            super("TRANSACTION_AMOUNT_NOT_POSITIVE", "O valor da movimentação precisa ser maior que zero.");
        }
    }

    /**
     * Competência de renda ({@code incomeCompetence}, ADR-017) fora da janela 2000-01..2100-12 ou em movimentação que não é
     * receita (despesa, transferência, ajuste... nunca mudam de mês).
     */
    public static class IncomeCompetenceInvalidException extends BusinessRuleException {
        public IncomeCompetenceInvalidException(String reason) {
            super("INCOME_COMPETENCE_INVALID", "Competência de renda inválida: " + reason);
        }
    }

    public static class PaymentMethodRequiredException extends BusinessRuleException {
        public PaymentMethodRequiredException(TransactionType type) {
            super("TRANSACTION_PAYMENT_METHOD_REQUIRED", "O meio de pagamento é obrigatório em " + type + ".");
        }
    }

    /** Gasto, receita, empréstimo, pagamento e reembolso em conta exigem conta líquida (CASH). */
    public static class AccountNotCashException extends BusinessRuleException {
        public AccountNotCashException(TransactionType type) {
            super("TRANSACTION_ACCOUNT_NOT_CASH", "A movimentação " + type + " exige uma conta líquida (corrente, poupança ou carteira).");
        }
    }

    public static class SameAccountTransferException extends BusinessRuleException {
        public SameAccountTransferException() {
            super("TRANSFER_SAME_ACCOUNT", "A transferência precisa de contas de origem e destino diferentes.");
        }
    }

    /** Entre classes de liquidez diferentes o tipo correto é INVESTMENT. */
    public static class TransferAcrossLiquidityException extends BusinessRuleException {
        public TransferAcrossLiquidityException() {
            super("TRANSFER_ACROSS_LIQUIDITY", "Transferência só entre contas da mesma classe; para investir ou resgatar use INVESTMENT.");
        }
    }

    public static class InvestmentAccountsMismatchException extends BusinessRuleException {
        public InvestmentAccountsMismatchException() {
            super("INVESTMENT_ACCOUNTS_MISMATCH", "O investimento exige uma conta de investimento e uma conta líquida.");
        }
    }

    public static class AdjustmentReasonRequiredException extends BusinessRuleException {
        public AdjustmentReasonRequiredException() {
            super("ADJUSTMENT_REASON_REQUIRED", "O ajuste manual exige um motivo.");
        }
    }

    /** Rendimento (S-26) só em conta de investimento. */
    public static class YieldRequiresInvestmentAccountException extends BusinessRuleException {
        public YieldRequiresInvestmentAccountException() {
            super("ADJUSTMENT_YIELD_REQUIRES_INVESTMENT_ACCOUNT", "Rendimento só pode ser lançado em conta de investimento.");
        }
    }

    /**
     * S-9: reembolso não passa do valor efetivamente cobrado (na compra no cartão, as parcelas não canceladas) menos os
     * reembolsos anteriores.
     */
    public static class RefundExceedsOriginalException extends BusinessRuleException {
        public RefundExceedsOriginalException() {
            super("REFUND_EXCEEDS_ORIGINAL", "O reembolso passa do valor cobrado na movimentação original menos os reembolsos anteriores.");
        }
    }

    public static class InvalidRefundOriginException extends BusinessRuleException {
        public InvalidRefundOriginException(String reason) {
            super("REFUND_INVALID_ORIGINAL", "Movimentação original do reembolso inválida: " + reason);
        }
    }

    public static class TransactionAlreadyVoidedException extends BusinessRuleException {
        public TransactionAlreadyVoidedException() {
            super("TRANSACTION_ALREADY_VOIDED", "A movimentação já está estornada.");
        }
    }

    public static class VoidReasonRequiredException extends BusinessRuleException {
        public VoidReasonRequiredException() {
            super("TRANSACTION_VOID_REASON_REQUIRED", "O estorno exige um motivo.");
        }
    }

    /** A guarda (S-36) recusou o estorno; o {@code code} vem da regra (ex.: TRANSACTION_IN_CLOSED_INVOICE). */
    public static class TransactionVoidRefusedException extends BusinessRuleException {
        public TransactionVoidRefusedException(VoidDecision decision) {
            super(decision.code(), "Estorno recusado: " + decision.reason());
        }
    }

    /** Erro de ligação: a policy usada não cobre esse tipo de movimentação (bug, vira 500). */
    public static class VoidPolicyNotApplicableException extends DomainException {
        public VoidPolicyNotApplicableException(TransactionType type) {
            super("TRANSACTION_VOID_POLICY_NOT_APPLICABLE", "A policy de estorno não cobre " + type + ".");
        }
    }

    /**
     * O estorno pediu um contexto que não veio (cartão e cadeia para movimentação de cartão; dívida para movimentação de
     * dívida). Erro de ligação do caso de uso (bug, vira 500).
     */
    public static class VoidContextMissingException extends DomainException {
        public VoidContextMissingException(TransactionType type, String missing) {
            super("TRANSACTION_VOID_CONTEXT_MISSING", "O estorno de " + type + " exige " + missing + ".");
        }
    }

    /** Operação que não existe para o tipo da movimentação (ex.: cancelar parcelas de uma despesa). */
    public static class InvalidTransactionOperationException extends BusinessRuleException {
        public InvalidTransactionOperationException(String code, String message) {
            super(code, message);
        }
    }

    /**
     * O estado gravado de uma movimentação não passa nas invariantes ao ser reidratado ({@code Transaction.restore}):
     * dado corrompido ou bug de mapeamento, nunca erro do usuário (vira 500).
     */
    public static class TransactionStateInconsistentException extends DomainException {
        public TransactionStateInconsistentException(String reason) {
            super("TRANSACTION_STATE_INCONSISTENT", "Estado gravado da movimentação inconsistente: " + reason);
        }

        public TransactionStateInconsistentException(String reason, Throwable cause) {
            super("TRANSACTION_STATE_INCONSISTENT", "Estado gravado da movimentação inconsistente: " + reason, cause);
        }
    }

    /** Tipo que existe no domínio mas ainda não nesta etapa (S-58): cartão, fatura e dívida. */
    public static class TransactionTypeNotAvailableException extends BusinessRuleException {
        public TransactionTypeNotAvailableException(TransactionType type) {
            super("TRANSACTION_TYPE_NOT_AVAILABLE", "O tipo " + type + " ainda não está disponível.");
        }
    }

    /**
     * Divisão de despesa (S-40, S-137): cada parte tem de ser maior que zero e Σ das partes tem de ficar <b>abaixo</b> do valor
     * da despesa (a parte do próprio usuário é o que sobra). Pagar tudo "pelo João" é empréstimo ({@code LOAN_GIVEN}).
     */
    public static class SharesExceedAmountException extends BusinessRuleException {
        public SharesExceedAmountException() {
            super("SHARES_EXCEED_AMOUNT", "As partes devem ser maiores que zero e somar menos que o valor da despesa; "
                    + "se a pessoa deve o valor inteiro, registre um empréstimo.");
        }
    }

    /** A pessoa de uma parte da despesa está arquivada e não recebe dívida nova. */
    public static class SharePersonArchivedException extends BusinessRuleException {
        public SharePersonArchivedException() {
            super("SHARE_PERSON_ARCHIVED", "A pessoa de uma das partes está arquivada e não recebe dívida nova.");
        }
    }

    /** A mesma {@code Idempotency-Key} foi usada com outro comando (S-72): 409. */
    public static class IdempotencyKeyReusedException extends ConflictException {
        public IdempotencyKeyReusedException() {
            super("IDEMPOTENCY_KEY_REUSED", "A Idempotency-Key já foi usada com outro conteúdo.");
        }
    }

    /** A movimentação não pode ser editada (estornada, ou o {@code type} do corpo é outro): 422 (S-68). */
    public static class TransactionNotEditableException extends BusinessRuleException {
        public TransactionNotEditableException(String reason) {
            super("TRANSACTION_NOT_EDITABLE", "A movimentação não pode ser editada: " + reason);
        }
    }

    /** S-81 (a): a despesa tem reembolsos ativos e a edição trocaria a categoria dela. */
    public static class RefundCategoryLockedException extends BusinessRuleException {
        public RefundCategoryLockedException() {
            super("REFUND_CATEGORY_LOCKED", "A despesa tem reembolsos ativos: a categoria não pode mudar.");
        }
    }
}
