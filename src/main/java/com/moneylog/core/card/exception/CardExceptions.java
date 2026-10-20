package com.moneylog.core.card.exception;

import java.util.UUID;

import com.moneylog.core.sk.exception.BusinessRuleException;
import com.moneylog.core.sk.exception.ConflictException;
import com.moneylog.core.sk.exception.DomainException;
import com.moneylog.core.sk.exception.NotFoundException;

public final class CardExceptions {

    private CardExceptions() {
    }

    public static class CardNotFoundException extends NotFoundException {
        public CardNotFoundException(UUID id) {
            super("CARD_NOT_FOUND", "Cartão não encontrado: " + id + ".");
        }
    }

    public static class InvoiceNotFoundException extends NotFoundException {
        public InvoiceNotFoundException(UUID id) {
            super("INVOICE_NOT_FOUND", "Fatura não encontrada: " + id + ".");
        }
    }

    public static class InvoiceDocumentAmountInvalidException extends BusinessRuleException {
        public InvoiceDocumentAmountInvalidException(String reason) {
            super("INVOICE_DOCUMENT_AMOUNT_INVALID", "Valor do documento da fatura inválido: " + reason);
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

    public static class CardInstitutionTooLongException extends BusinessRuleException {
        public CardInstitutionTooLongException(int max) {
            super("CARD_INSTITUTION_TOO_LONG", "A instituição do cartão pode ter no máximo " + max + " caracteres.");
        }
    }

    public static class CardBrandTooLongException extends BusinessRuleException {
        public CardBrandTooLongException(int max) {
            super("CARD_BRAND_TOO_LONG", "A bandeira do cartão pode ter no máximo " + max + " caracteres.");
        }
    }

    public static class CardLastDigitsInvalidException extends BusinessRuleException {
        public CardLastDigitsInvalidException(String reason) {
            super("CARD_LAST_DIGITS_INVALID", "Finais do cartão inválidos: " + reason);
        }
    }

    public static class PurchaseLastDigitsNotFromCardException extends BusinessRuleException {
        public PurchaseLastDigitsNotFromCardException(String lastDigits) {
            super("PURCHASE_LAST_DIGITS_NOT_FROM_CARD", "O final " + lastDigits + " não pertence a este cartão.");
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

    public static class CardNotArchivedException extends BusinessRuleException {
        public CardNotArchivedException() {
            super("CARD_NOT_ARCHIVED", "O cartão não está arquivado.");
        }
    }

    public static class CardHasPendingBalanceException extends BusinessRuleException {
        public CardHasPendingBalanceException() {
            super("CARD_HAS_PENDING_BALANCE",
                    "O cartão tem fatura com saldo, crédito no cartão ou parcelas futuras: quite ou cancele antes de arquivar.");
        }
    }

    public static class BillingChangeNotConfirmedException extends BusinessRuleException {
        public BillingChangeNotConfirmedException() {
            super("CARD_BILLING_CHANGE_NOT_CONFIRMED",
                    "Mudar o fechamento ou o vencimento exige confirmar a prévia (confirmedEffectiveFrom).");
        }
    }

    public static class CardBillingChangeStaleException extends ConflictException {
        public CardBillingChangeStaleException() {
            super("CARD_BILLING_CHANGE_STALE", "A prévia da mudança de dias ficou desatualizada: peça a prévia de novo.");
        }
    }

    public static class InvalidInstallmentCountException extends BusinessRuleException {
        public InvalidInstallmentCountException(int count) {
            super("CARD_INVALID_INSTALLMENT_COUNT", "Número de parcelas inválido: " + count + ". Use de 1 a 36.");
        }
    }

    public static class InvalidInvoiceOperationException extends BusinessRuleException {
        public InvalidInvoiceOperationException(String code, String message) {
            super(code, message);
        }
    }

    public static class NoInstallmentsToCancelException extends BusinessRuleException {
        public NoInstallmentsToCancelException() {
            super("CARD_NO_INSTALLMENTS_TO_CANCEL", "Não há parcelas em faturas abertas ou futuras para cancelar.");
        }
    }

    public static class InvoiceClosingDateNotFoundException extends BusinessRuleException {
        public InvoiceClosingDateNotFoundException(java.time.LocalDate closingDate) {
            super("INVOICE_NOT_FOUND", "Não existe fatura do cartão que feche em " + closingDate + ".");
        }
    }

    public static class InvoiceChainInconsistentException extends DomainException {
        public InvoiceChainInconsistentException(String reason) {
            super("INVOICE_CHAIN_INCONSISTENT", "Cadeia de faturas inconsistente: " + reason);
        }
    }

    public static class InvoiceResolutionCollidedException extends ConflictException {
        public InvoiceResolutionCollidedException(UUID invoiceId, Throwable cause) {
            super("CONFLICT", "A fatura " + invoiceId + " colidiu com uma alteração concorrente do cartão.");
            initCause(cause);
        }
    }

    public static class StaleBillingChangeException extends DomainException {
        public StaleBillingChangeException(UUID cardId) {
            super("CARD_BILLING_CHANGE_STALE", "A mudança de fechamento/vencimento não corresponde às regras atuais do cartão " + cardId + ".");
        }
    }

    public static class InvalidInstallmentException extends DomainException {
        public InvalidInstallmentException(String reason) {
            super("CARD_INVALID_INSTALLMENT", "Parcela inválida: " + reason);
        }
    }

    public static class OngoingInvalidCountException extends BusinessRuleException {
        public OngoingInvalidCountException(int total, int remaining) {
            super("ONGOING_INVALID_COUNT", "As parcelas que faltam (" + remaining + ") não podem passar do total de parcelas (" + total + ").");
        }
    }

    public static class HistoricalInvoiceNotClosedException extends BusinessRuleException {
        public HistoricalInvoiceNotClosedException(java.time.LocalDate closingDate) {
            super("HISTORICAL_INVOICE_NOT_CLOSED", "A fatura que fecha em " + closingDate + " ainda não fechou: a fatura histórica é só de fatura já fechada.");
        }
    }

    public static class OngoingFirstInvoiceNotPayableException extends BusinessRuleException {
        public OngoingFirstInvoiceNotPayableException(java.time.LocalDate closingDate) {
            super("ONGOING_FIRST_INVOICE_NOT_PAYABLE",
                    "A fatura que fecha em " + closingDate + " já está paga; escolha a fatura corrente ou uma futura para a próxima parcela.");
        }
    }

    public static class OngoingCategoryRetroactiveException extends BusinessRuleException {
        public OngoingCategoryRetroactiveException() {
            super("ONGOING_CATEGORY_RETROACTIVE",
                    "Com categoria, a próxima parcela não pode cair em mês anterior ao atual (não se cria consumo no passado); "
                            + "use a fatura corrente ou uma futura, ou cadastre sem categoria.");
        }
    }
}
