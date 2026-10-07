package com.moneylog.core.finance.app.accounts;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.moneylog.core.account.domain.Account;
import com.moneylog.core.finance.domain.CashPosition;
import com.moneylog.core.finance.domain.FinancialRecords;
import com.moneylog.core.finance.domain.GoalReserves;
import com.moneylog.core.finance.domain.Ledger;
import com.moneylog.core.sk.ledger.LedgerEntry;
import com.moneylog.core.sk.ledger.Posting;
import com.moneylog.core.sk.ledger.PostingSourceType;
import com.moneylog.core.sk.persistence.Persisted;
import com.moneylog.core.transaction.domain.Transaction;

/**
 * Monta {@link AccountDetails} a partir dos {@link FinancialRecords} carregados uma vez (S-79): o saldo sai de
 * {@code records.ledger()}, o mesmo cálculo do dashboard.
 */
public final class AccountViews {

    private AccountViews() {
    }

    public static AccountDetails of(Persisted<Account> persisted, FinancialRecords records, LocalDate today) {
        Ledger ledger = records.ledger();
        return of(persisted, records, today, ledger, GoalReserves.at(records, CashPosition.at(records, ledger, today)));
    }

    /** Mesma visão com o razão e as reservas já calculados (uma vez para a página inteira). */
    public static AccountDetails of(Persisted<Account> persisted, FinancialRecords records, LocalDate today, Ledger ledger,
                                    GoalReserves reserves) {
        Account account = persisted.value();
        List<Posting> accountPostings = ledger.entries().stream().map(LedgerEntry::posting)
                .filter(posting -> account.book().equals(posting.book())).toList();
        return new AccountDetails(account, persisted.createdAt(), persisted.updatedAt(),
                ledger.balance(account.book(), today), !hasActiveMovements(records, account.id()),
                account.canBeArchived(accountPostings, today), reserves.reservedOn(account.id()), reserves.isUncovered(account.id()));
    }

    /**
     * A conta tem movimentação ativa? Sim se alguma movimentação ativa a usa (origem ou contrapartida) ou se existe
     * lançamento ativo no livro dela que não seja o saldo inicial (ex.: dívida sem movimentação).
     */
    public static boolean hasActiveMovements(FinancialRecords records, UUID accountId) {
        for (Transaction transaction : records.transactions()) {
            if (transaction.isActive()
                    && (accountId.equals(transaction.accountId()) || accountId.equals(transaction.counterpartAccountId()))) {
                return true;
            }
        }
        return records.entries().stream().anyMatch(entry -> entry.active()
                && entry.source().type() != PostingSourceType.ACCOUNT_OPENING
                && entry.posting().book().type() == com.moneylog.core.sk.ledger.BookType.ACCOUNT
                && accountId.equals(entry.posting().book().id()));
    }
}
