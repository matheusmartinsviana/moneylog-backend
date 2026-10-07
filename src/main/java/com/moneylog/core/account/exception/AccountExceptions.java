package com.moneylog.core.account.exception;

import java.time.LocalDate;
import java.util.UUID;

import com.moneylog.core.sk.exception.BusinessRuleException;
import com.moneylog.core.sk.exception.NotFoundException;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;

/**
 * Exceções da feature conta.
 */
public final class AccountExceptions {

    private AccountExceptions() {
    }

    /** Conta inexistente ou de outro usuário (não revela existência). */
    public static class AccountNotFoundException extends NotFoundException {
        public AccountNotFoundException(UUID accountId) {
            super("ACCOUNT_NOT_FOUND", "Conta não encontrada: " + accountId + ".");
        }
    }

    public static class AccountOwnerRequiredException extends BusinessRuleException {
        public AccountOwnerRequiredException() {
            super("ACCOUNT_OWNER_REQUIRED", "A conta precisa pertencer a um usuário.");
        }
    }

    public static class AccountNameRequiredException extends BusinessRuleException {
        public AccountNameRequiredException() {
            super("ACCOUNT_NAME_REQUIRED", "O nome da conta é obrigatório.");
        }
    }

    public static class AccountNameTooLongException extends BusinessRuleException {
        public AccountNameTooLongException(int max) {
            super("ACCOUNT_NAME_TOO_LONG", "O nome da conta pode ter no máximo " + max + " caracteres.");
        }
    }

    /** A instituição cabe na coluna {@code accounts.institution} (VARCHAR(100)). */
    public static class AccountInstitutionTooLongException extends BusinessRuleException {
        public AccountInstitutionTooLongException(int max) {
            super("ACCOUNT_INSTITUTION_TOO_LONG", "A instituição da conta pode ter no máximo " + max + " caracteres.");
        }
    }

    /** Campo obrigatório ausente (id, tipo, saldo inicial, data de abertura). */
    public static class AccountFieldRequiredException extends BusinessRuleException {
        public AccountFieldRequiredException(String field) {
            super("ACCOUNT_FIELD_REQUIRED", "Campo obrigatório da conta ausente: " + field + ".");
        }
    }

    /** Só BRL no MVP (S-2). */
    public static class AccountCurrencyNotSupportedException extends BusinessRuleException {
        public AccountCurrencyNotSupportedException(CurrencyCode currency) {
            super("ACCOUNT_CURRENCY_NOT_SUPPORTED", "Moeda não suportada para conta: " + currency + ". Use BRL.");
        }
    }

    /** Só arquiva com saldo zero (S-4). */
    public static class AccountWithBalanceCannotBeArchivedException extends BusinessRuleException {
        public AccountWithBalanceCannotBeArchivedException(Money balance) {
            super("ACCOUNT_WITH_BALANCE_CANNOT_BE_ARCHIVED",
                    "A conta só pode ser arquivada com saldo zero (saldo atual: " + balance + ").");
        }
    }

    /** S-4: zero hoje, mas uma movimentação futura deixaria a conta arquivada com saldo. */
    public static class AccountWithFutureBalanceCannotBeArchivedException extends BusinessRuleException {
        public AccountWithFutureBalanceCannotBeArchivedException(LocalDate date, Money balance) {
            super("ACCOUNT_WITH_FUTURE_BALANCE_CANNOT_BE_ARCHIVED",
                    "A conta tem movimentação futura: o saldo em " + date + " seria " + balance + ". Só arquiva com saldo zero em todas as datas.");
        }
    }

    /**
     * S-259 (A1 da revisão da Etapa 5): a conta tem reserva ativa positiva de meta; arquivar a deixaria abatendo o disponível por uma
     * conta que o usuário já não vê. Libere a reserva pela meta antes.
     */
    public static class AccountHasReservationsException extends BusinessRuleException {
        public AccountHasReservationsException(java.util.List<String> goalNames) {
            super("ACCOUNT_HAS_RESERVATIONS",
                    "A conta tem reserva ativa de meta (" + String.join(", ", goalNames) + "). Libere a reserva pela meta antes de arquivar a conta.");
        }
    }

    /** Conta arquivada não recebe movimentação nova. */
    public static class AccountArchivedException extends BusinessRuleException {
        public AccountArchivedException() {
            super("ACCOUNT_ARCHIVED", "A conta está arquivada e não recebe movimentações novas.");
        }
    }

    public static class AccountAlreadyArchivedException extends BusinessRuleException {
        public AccountAlreadyArchivedException() {
            super("ACCOUNT_ALREADY_ARCHIVED", "A conta já está arquivada.");
        }
    }

    public static class AccountNotArchivedException extends BusinessRuleException {
        public AccountNotArchivedException() {
            super("ACCOUNT_NOT_ARCHIVED", "A conta não está arquivada.");
        }
    }

    /** Tipo, saldo inicial e data de abertura só mudam enquanto a conta não tem movimentação ativa (D1 §2.2). */
    public static class AccountOpeningLockedException extends BusinessRuleException {
        public AccountOpeningLockedException() {
            super("ACCOUNT_OPENING_LOCKED", "A conta já tem movimentações: tipo, saldo inicial e data de abertura não podem mais ser corrigidos.");
        }
    }
}
