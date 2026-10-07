package com.moneylog.core.finance.app.accounts.usecase;

import java.time.LocalDate;
import java.util.UUID;

import com.moneylog.core.finance.app.accounts.AccountDetails;
import com.moneylog.core.account.domain.AccountType;
import com.moneylog.core.sk.money.Money;

public interface UpdateAccountUseCase {

    /**
     * Edita nome e instituição sempre; {@code type}, {@code openingBalance} e {@code openingDate} só se a conta não
     * tem movimentação ativa (senão {@code ACCOUNT_OPENING_LOCKED}). A correção do saldo inicial desativa o par de
     * lançamentos antigo e grava um novo (D1 §2.2). Campo de saldo inicial igual ao atual não conta como mudança
     * (S-100).
     *
     * @throws com.moneylog.core.sk.exception.VersionConflictException versão desatualizada
     */
    AccountDetails handle(UpdateAccountCommand command);

    /** Campos {@code null} (exceto nome e versão) não foram enviados. */
    record UpdateAccountCommand(UUID userId, UUID accountId, String name, String institution, AccountType type,
                                Money openingBalance, LocalDate openingDate, long version) {
    }
}
