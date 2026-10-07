package com.moneylog.core.finance.app.export;

import java.time.Instant;
import java.util.List;

import com.moneylog.core.account.domain.Account;
import com.moneylog.core.card.domain.CreditCard;
import com.moneylog.core.category.domain.Category;
import com.moneylog.core.debt.domain.Debt;
import com.moneylog.core.debt.domain.Person;
import com.moneylog.core.goal.domain.Goal;
import com.moneylog.core.recurrence.domain.RecurringTransaction;
import com.moneylog.core.sk.ledger.LedgerEntry;
import com.moneylog.core.transaction.domain.Transaction;

/**
 * Todos os dados financeiros do usuário (S-153), como o domínio os conhece. Não há segredo aqui: o usuário (hash de senha), os
 * refresh tokens e as chaves de idempotência não fazem parte do backup. {@code ledgerPostings} são os lançamentos do razão (ativos e
 * inativos), então o saldo de cada conta pode ser recalculado a partir do arquivo. As notas do vault entram na Etapa 6.
 */
public record BackupSnapshot(int schemaVersion, Instant generatedAt, List<Account> accounts, List<Category> categories,
                             List<CreditCard> cards, List<Transaction> transactions, List<Debt> debts, List<Person> people,
                             List<RecurringTransaction> recurrences, List<Goal> goals, List<LedgerEntry> ledgerPostings) {

    /** Versão do formato do arquivo: sobe quando um campo muda de sentido (campos novos são aditivos e não sobem). */
    public static final int SCHEMA_VERSION = 1;
}
