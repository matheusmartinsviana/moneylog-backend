package com.moneylog.core.card.exception;

import java.util.UUID;

import com.moneylog.core.sk.exception.BusinessRuleException;
import com.moneylog.core.sk.exception.ConflictException;
import com.moneylog.core.sk.exception.DomainException;
import com.moneylog.core.sk.exception.NotFoundException;

/**
 * Exceções da feature cartão (cartão, calendário, parcelas e fatura).
 */
public final class CardExceptions {

    private CardExceptions() {
    }

    public static class CardNotFoundException extends NotFoundException {
        public CardNotFoundException(UUID id) {
            super("CARD_NOT_FOUND", "Cartão não encontrado: " + id + ".");
        }
    }

    /** Fatura inexistente, de outro usuário/cartão ou fora da janela de resolução do id (ADR-006 §6). */
    public static class InvoiceNotFoundException extends NotFoundException {
        public InvoiceNotFoundException(UUID id) {
            super("INVOICE_NOT_FOUND", "Fatura não encontrada: " + id + ".");
        }
    }

    public static class CardFieldRequiredException extends BusinessRuleException {
        public CardFieldRequiredException(String field) {
            super("CARD_FIELD_REQUIRED", "Campo obrigatório do cartão ausente: " + field + ".");
        }
    }

    public static class CardNameRequiredException extends BusinessRuleException {
        public CardNameRequiredException() {
            super("CARD_NAME_REQUIRED", "O nome do cartão é obrigatório.");
        }
    }

    public static class CardNameTooLongException extends BusinessRuleException {
        public CardNameTooLongException(int max) {
            super("CARD_NAME_TOO_LONG", "O nome do cartão pode ter no máximo " + max + " caracteres.");
        }
    }

    /** A instituição cabe na coluna {@code credit_cards.institution} (VARCHAR(100)). */
    public static class CardInstitutionTooLongException extends BusinessRuleException {
        public CardInstitutionTooLongException(int max) {
            super("CARD_INSTITUTION_TOO_LONG", "A instituição do cartão pode ter no máximo " + max + " caracteres.");
        }
    }

    public static class InvalidCardLimitException extends BusinessRuleException {
        public InvalidCardLimitException() {
            super("CARD_INVALID_LIMIT", "O limite do cartão precisa ser maior que zero, em BRL.");
        }
    }

    public static class InvalidBillingDayException extends BusinessRuleException {
        public InvalidBillingDayException(String field, int day) {
            super("CARD_INVALID_BILLING_DAY", "Dia de " + field + " inválido: " + day + ". Use um valor de 1 a 31.");
        }
    }

    public static class CardArchivedException extends BusinessRuleException {
        public CardArchivedException() {
            super("CARD_ARCHIVED", "O cartão está arquivado e não recebe movimentações novas.");
        }
    }

    public static class CardAlreadyArchivedException extends BusinessRuleException {
        public CardAlreadyArchivedException() {
            super("CARD_ALREADY_ARCHIVED", "O cartão já está arquivado.");
        }
    }

    /** Desarquivar um cartão que não está arquivado. */
    public static class CardNotArchivedException extends BusinessRuleException {
        public CardNotArchivedException() {
            super("CARD_NOT_ARCHIVED", "O cartão não está arquivado.");
        }
    }

    /**
     * S-120: o cartão só arquiva sem fatura com saldo em aberto, sem crédito transportado e sem parcela futura. O
     * saldo, o crédito e as parcelas são do razão e da cadeia; quem decide é o caso de uso (precisa das movimentações).
     */
    public static class CardHasPendingBalanceException extends BusinessRuleException {
        public CardHasPendingBalanceException() {
            super("CARD_HAS_PENDING_BALANCE",
                    "O cartão tem fatura com saldo, crédito no cartão ou parcelas futuras: quite ou cancele antes de arquivar.");
        }
    }

    /**
     * A mudança de fechamento/vencimento exige a confirmação da prévia ({@code confirmedEffectiveFrom}, S-128): o usuário vê
     * antes a partir de qual fatura os dias novos valem.
     */
    public static class BillingChangeNotConfirmedException extends BusinessRuleException {
        public BillingChangeNotConfirmedException() {
            super("CARD_BILLING_CHANGE_NOT_CONFIRMED",
                    "Mudar o fechamento ou o vencimento exige confirmar a prévia (confirmedEffectiveFrom).");
        }
    }

    /**
     * A prévia ficou velha (S-128): entre ela e o {@code PUT} a fatura corrente virou, então a primeira fatura afetada é
     * outra. Nada muda; o cliente busca a prévia de novo.
     */
    public static class CardBillingChangeStaleException extends ConflictException {
        public CardBillingChangeStaleException() {
            super("CARD_BILLING_CHANGE_STALE", "A prévia da mudança de dias ficou desatualizada: peça a prévia de novo.");
        }
    }

    /** S-7: parcelamento de 1 a 36. */
    public static class InvalidInstallmentCountException extends BusinessRuleException {
        public InvalidInstallmentCountException(int count) {
            super("CARD_INVALID_INSTALLMENT_COUNT", "Número de parcelas inválido: " + count + ". Use de 1 a 36.");
        }
    }

    /** Operação não permitida no status atual da fatura (D1 §2.5.3); o {@code code} é estável. */
    public static class InvalidInvoiceOperationException extends BusinessRuleException {
        public InvalidInvoiceOperationException(String code, String message) {
            super(code, message);
        }
    }

    /** Nada a cancelar: nenhuma parcela em fatura FUTURE/OPEN. */
    public static class NoInstallmentsToCancelException extends BusinessRuleException {
        public NoInstallmentsToCancelException() {
            super("CARD_NO_INSTALLMENTS_TO_CANCEL", "Não há parcelas em faturas abertas ou futuras para cancelar.");
        }
    }

    /**
     * A data informada não é um fechamento do calendário do cartão (A3): pagamento ou item apontando para uma
     * "fatura" que não existe. Erro de entrada, vira 422.
     */
    public static class InvoiceClosingDateNotFoundException extends BusinessRuleException {
        public InvoiceClosingDateNotFoundException(java.time.LocalDate closingDate) {
            super("INVOICE_NOT_FOUND", "Não existe fatura do cartão que feche em " + closingDate + ".");
        }
    }

    /**
     * Invariante interna da cadeia de faturas quebrada (bug, vira 500): item ou pagamento com data de fechamento fora
     * do calendário (C1), cadeia de outro cartão (A3) ou realocação inconsistente.
     */
    public static class InvoiceChainInconsistentException extends DomainException {
        public InvoiceChainInconsistentException(String reason) {
            super("INVOICE_CHAIN_INCONSISTENT", "Cadeia de faturas inconsistente: " + reason);
        }
    }

    /** A mudança de dias foi planejada sobre outro cartão ou sobre regras que já mudaram (bug de ligação, vira 500). */
    public static class StaleBillingChangeException extends DomainException {
        public StaleBillingChangeException(UUID cardId) {
            super("CARD_BILLING_CHANGE_STALE", "A mudança de fechamento/vencimento não corresponde às regras atuais do cartão " + cardId + ".");
        }
    }

    /** Invariante interna da parcela quebrada (bug, vira 500). */
    public static class InvalidInstallmentException extends DomainException {
        public InvalidInstallmentException(String reason) {
            super("CARD_INVALID_INSTALLMENT", "Parcela inválida: " + reason);
        }
    }

    /** Compra parcelada em andamento com contagem incoerente (restantes acima do total). */
    public static class OngoingInvalidCountException extends BusinessRuleException {
        public OngoingInvalidCountException(int total, int remaining) {
            super("ONGOING_INVALID_COUNT", "As parcelas que faltam (" + remaining + ") não podem passar do total de parcelas (" + total + ").");
        }
    }

    /** A fatura da próxima parcela já está paga: a compra em andamento não pode cair nela. */
    public static class OngoingFirstInvoiceNotPayableException extends BusinessRuleException {
        public OngoingFirstInvoiceNotPayableException(java.time.LocalDate closingDate) {
            super("ONGOING_FIRST_INVOICE_NOT_PAYABLE",
                    "A fatura que fecha em " + closingDate + " já está paga; escolha a fatura corrente ou uma futura para a próxima parcela.");
        }
    }

    /** Com categoria, a compra em andamento geraria consumo em mês passado (P5): a competência da primeira parcela não pode ser anterior à de hoje. */
    public static class OngoingCategoryRetroactiveException extends BusinessRuleException {
        public OngoingCategoryRetroactiveException() {
            super("ONGOING_CATEGORY_RETROACTIVE",
                    "Com categoria, a próxima parcela não pode cair em mês anterior ao atual (não se cria consumo no passado); "
                            + "use a fatura corrente ou uma futura, ou cadastre sem categoria.");
        }
    }
}
