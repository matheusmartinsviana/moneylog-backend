package com.moneylog.core.account.exception;

import java.time.LocalDate;
import java.util.UUID;

import com.moneylog.core.sk.exception.BusinessRuleException;
import com.moneylog.core.sk.exception.NotFoundException;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;

public final class AccountExceptions {

    private AccountExceptions() {
    }

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

    public static class AccountInstitutionTooLongException extends BusinessRuleException {
        public AccountInstitutionTooLongException(int max) {
            super("ACCOUNT_INSTITUTION_TOO_LONG", "A instituição da conta pode ter no máximo " + max + " caracteres.");
        }
    }

    public static class AccountFieldRequiredException extends BusinessRuleException {
        public AccountFieldRequiredException(String field) {
            super("ACCOUNT_FIELD_REQUIRED", "Campo obrigatório da conta ausente: " + field + ".");
        }
    }

    public static class AccountCurrencyNotSupportedException extends BusinessRuleException {
        public AccountCurrencyNotSupportedException(CurrencyCode currency) {
            super("ACCOUNT_CURRENCY_NOT_SUPPORTED", "Moeda não suportada para conta: " + currency + ". Use BRL.");
        }
    }

    public static class AccountWithBalanceCannotBeArchivedException extends BusinessRuleException {
        public AccountWithBalanceCannotBeArchivedException(Money balance) {
            super("ACCOUNT_WITH_BALANCE_CANNOT_BE_ARCHIVED",
                    "A conta só pode ser arquivada com saldo zero (saldo atual: " + balance + ").");
        }
    }

    public static class AccountWithFutureBalanceCannotBeArchivedException extends BusinessRuleException {
        public AccountWithFutureBalanceCannotBeArchivedException(LocalDate date, Money balance) {
            super("ACCOUNT_WITH_FUTURE_BALANCE_CANNOT_BE_ARCHIVED",
                    "A conta tem movimentação futura: o saldo em " + date + " seria " + balance + ". Só arquiva com saldo zero em todas as datas.");
        }
    }

    public static class AccountHasReservationsException extends BusinessRuleException {
        public AccountHasReservationsException(java.util.List<String> goalNames) {
            super("ACCOUNT_HAS_RESERVATIONS",
                    "A conta tem reserva ativa de meta (" + String.join(", ", goalNames) + "). Libere a reserva pela meta antes de arquivar a conta.");
        }
    }

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

    public static class AccountOpeningLockedException extends BusinessRuleException {
        public AccountOpeningLockedException() {
            super("ACCOUNT_OPENING_LOCKED", "A conta já tem movimentações: tipo, saldo inicial e data de abertura não podem mais ser corrigidos.");
        }
    }
}
