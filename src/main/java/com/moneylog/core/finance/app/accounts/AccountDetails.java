package com.moneylog.core.finance.app.accounts;

import java.time.Instant;
import java.util.Objects;

import com.moneylog.core.account.domain.Account;
import com.moneylog.core.sk.money.Money;

/**
 * Conta com o que a API calcula (S-62, S-79): saldo de hoje, se o saldo inicial ainda pode ser corrigido e se a conta
 * pode ser arquivada. O front só exibe e habilita ações; a recusa continua no backend.
 *
 * @param reservedAmount reservado nas metas na conta, até hoje ({@code GoalReserves}, Etapa 5); 0,00 sem metas
 * @param uncovered      reservado maior que o saldo (S-148): só sinaliza, nunca bloqueia
 */
public record AccountDetails(Account account, Instant createdAt, Instant updatedAt, Money currentBalance,
                             boolean openingEditable, boolean archivable, Money reservedAmount, boolean uncovered) {

    public AccountDetails {
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(currentBalance, "currentBalance");
        Objects.requireNonNull(reservedAmount, "reservedAmount");
    }

    /** Sem reservas de metas (reservado 0,00): o comportamento das Etapas 2 a 4. */
    public AccountDetails(Account account, Instant createdAt, Instant updatedAt, Money currentBalance, boolean openingEditable,
                          boolean archivable) {
        this(account, createdAt, updatedAt, currentBalance, openingEditable, archivable,
                Money.zero(currentBalance.currency()), false);
    }
}
