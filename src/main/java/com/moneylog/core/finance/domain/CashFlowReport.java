package com.moneylog.core.finance.domain;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;

import com.moneylog.core.sk.ledger.BookType;
import com.moneylog.core.sk.ledger.LedgerEntry;
import com.moneylog.core.sk.ledger.PostingSourceType;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.time.Competence;
import com.moneylog.core.transaction.domain.Transaction;

/**
 * Fluxo de caixa (D1 §2.8, §2.10): efeito líquido de cada <b>movimentação</b> nas contas {@code CASH}, pela data. Uma
 * transferência entre contas CASH tem efeito 0 e não entra; uma aplicação em investimento é saída; o saldo inicial
 * não é movimentação e não entra. É a mesma definição do {@code totals.cashFlow} da lista de registros.
 *
 * @param in    Σ efeitos positivos
 * @param out   Σ efeitos negativos, em valor positivo
 * @param net   in − out
 * @param count movimentações com efeito diferente de zero
 */
public record CashFlowReport(LocalDate from, LocalDate to, Money in, Money out, Money net, int count) {

    public static CashFlowReport forCompetence(FinancialRecords records, Ledger ledger, Competence competence) {
        return between(records, ledger, competence.firstDay(), competence.lastDay());
    }

    /** Todas as movimentações no período (o caminho do dashboard); é {@link #between(FinancialRecords, Ledger, LocalDate, LocalDate, Predicate)} sem filtro. */
    public static CashFlowReport between(FinancialRecords records, Ledger ledger, LocalDate from, LocalDate to) {
        return between(records, ledger, from, to, null);
    }

    /**
     * Efeito líquido de cada movimentação que passa em {@code filter} nas contas {@code CASH} (S-79: é a mesma função do
     * dashboard e do {@code totals.cashFlow} da lista; os filtros {@code type}/{@code categoryId}/{@code method}/{@code q}/
     * {@code status} viram o predicado). Estornada nunca entra (os lançamentos dela estão inativos).
     *
     * @param filter {@code null} = todas as movimentações
     */
    public static CashFlowReport between(FinancialRecords records, Ledger ledger, LocalDate from, LocalDate to,
                                         Predicate<Transaction> filter) {
        List<UUID> cash = records.cashAccountIds();
        return of(records, ledger, from, to, filter,
                e -> e.posting().bookType() == BookType.ACCOUNT && cash.contains(e.posting().book().id()));
    }

    /**
     * Efeito de cada movimentação que passa em {@code filter} <b>só no livro da conta informada</b> (S-63), seja ela
     * {@code CASH} ou de investimento: a transferência aparece como saída na origem e como entrada no destino; a
     * aplicação aparece como entrada na conta de investimento. O saldo inicial não é movimentação e não entra.
     *
     * @param filter {@code null} = todas as movimentações
     */
    public static CashFlowReport forAccount(FinancialRecords records, Ledger ledger, UUID accountId, LocalDate from, LocalDate to,
                                            Predicate<Transaction> filter) {
        Objects.requireNonNull(accountId, "accountId");
        return of(records, ledger, from, to, filter,
                e -> e.posting().bookType() == BookType.ACCOUNT && accountId.equals(e.posting().book().id()));
    }

    private static CashFlowReport of(FinancialRecords records, Ledger ledger, LocalDate from, LocalDate to,
                                     Predicate<Transaction> filter, Predicate<LedgerEntry> book) {
        Map<UUID, Transaction> transactions = filter == null ? Map.of() : records.transactionsById();
        Map<UUID, Money> byTransaction = new HashMap<>();
        for (LedgerEntry e : ledger.entries()) {
            LocalDate date = e.posting().effectiveDate();
            if (e.source().type() == PostingSourceType.TRANSACTION && book.test(e) && !date.isBefore(from) && !date.isAfter(to)
                    && accepts(filter, transactions.get(e.source().sourceId()))) {
                byTransaction.merge(e.source().sourceId(), e.posting().amount(), Money::add);
            }
        }
        Money in = Money.zero(CurrencyCode.BRL);
        Money out = Money.zero(CurrencyCode.BRL);
        int count = 0;
        for (Money effect : byTransaction.values()) {
            if (effect.isPositive()) {
                in = in.add(effect);
                count++;
            } else if (effect.isNegative()) {
                out = out.add(effect.negate());
                count++;
            }
        }
        return new CashFlowReport(from, to, in, out, in.subtract(out), count);
    }

    private static boolean accepts(Predicate<Transaction> filter, Transaction transaction) {
        return filter == null || (transaction != null && filter.test(transaction));
    }
}
