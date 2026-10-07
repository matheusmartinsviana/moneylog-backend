package com.moneylog.core.finance.app.accounts.usecase;

import java.util.UUID;

import com.moneylog.core.finance.app.accounts.AccountDetails;

public interface ArchiveAccountUseCase {

    /**
     * Arquiva a conta (S-4, S-92): só com saldo zero em todas as datas, contando movimentação futura e saldo inicial
     * com data futura; senão {@code ACCOUNT_WITH_BALANCE_CANNOT_BE_ARCHIVED} (ou a variante de saldo futuro).
     */
    AccountDetails handle(ArchiveAccountCommand command);

    record ArchiveAccountCommand(UUID userId, UUID accountId) {
    }
}
