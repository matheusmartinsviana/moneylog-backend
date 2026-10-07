package com.moneylog.core.finance.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.moneylog.core.account.domain.Account;
import com.moneylog.core.account.domain.LiquidityClass;
import com.moneylog.core.card.domain.CreditCard;
import com.moneylog.core.debt.domain.Debt;
import com.moneylog.core.goal.domain.Goal;
import com.moneylog.core.sk.ledger.LedgerEntry;
import com.moneylog.core.sk.ledger.PostingSource;
import com.moneylog.core.transaction.domain.Transaction;

/**
 * Os registros de um usuário que as lentes leem (D1 §1): contas, cartões, movimentações e dívidas. Na Etapa 1 as
 * calculadoras rodam em memória; na Etapa 2 o gateway entrega as mesmas entradas a partir de {@code ledger_postings}
 * (só postings ativos) e das tabelas de cadastro. As metas (Etapa 5) trazem as reservas lógicas: não são lançamentos e
 * não entram em {@link #entries()}; só o {@link GoalReserves} as lê (S-147).
 */
public record FinancialRecords(List<Account> accounts, List<CreditCard> cards, List<Transaction> transactions, List<Debt> debts,
                               List<Goal> goals) {

    public FinancialRecords {
        accounts = List.copyOf(accounts);
        cards = List.copyOf(cards);
        transactions = List.copyOf(transactions);
        debts = List.copyOf(debts);
        goals = List.copyOf(goals);
    }

    /** Sem metas (comportamento das Etapas 2 a 4). */
    public FinancialRecords(List<Account> accounts, List<CreditCard> cards, List<Transaction> transactions, List<Debt> debts) {
        this(accounts, cards, transactions, debts, List.of());
    }

    /**
     * Todos os lançamentos, de todas as origens (S-45): saldo inicial ({@code ACCOUNT_OPENING}), movimentações
     * ({@code TRANSACTION}) e dívidas ({@code DEBT_RECOGNITION}, {@code DEBT_CANCELLATION}).
     */
    public List<LedgerEntry> entries() {
        List<LedgerEntry> entries = new ArrayList<>();
        for (Account account : accounts) {
            if (!account.openingPostings().isEmpty()) {
                entries.addAll(LedgerEntry.activeGroup(PostingSource.accountOpening(account.id()), account.openingPostings()));
            }
        }
        transactions.forEach(t -> entries.addAll(t.entries()));
        debts.forEach(d -> entries.addAll(d.entries()));
        return List.copyOf(entries);
    }

    public Ledger ledger() {
        return new Ledger(entries());
    }

    public Optional<Transaction> transaction(UUID id) {
        return transactions.stream().filter(t -> t.id().equals(id)).findFirst();
    }

    Map<UUID, Transaction> transactionsById() {
        return transactions.stream().collect(Collectors.toMap(Transaction::id, Function.identity()));
    }

    /** Ids das contas líquidas (CASH). */
    List<UUID> cashAccountIds() {
        return accounts.stream().filter(a -> a.liquidity() == LiquidityClass.CASH).map(Account::id).toList();
    }

    List<UUID> investmentAccountIds() {
        return accounts.stream().filter(a -> a.liquidity() == LiquidityClass.INVESTMENT).map(Account::id).toList();
    }
}
