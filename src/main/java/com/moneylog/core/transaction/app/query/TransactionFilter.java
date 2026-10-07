package com.moneylog.core.transaction.app.query;

import java.time.LocalDate;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Predicate;

import com.moneylog.core.transaction.domain.PaymentMethod;
import com.moneylog.core.transaction.domain.Transaction;
import com.moneylog.core.transaction.domain.TransactionStatus;
import com.moneylog.core.transaction.domain.TransactionType;

/**
 * Filtro da lista de movimentações (D2 §4.2, S-71, S-79, S-80): a <b>única</b> definição de "a movimentação casa com o
 * filtro". Ela vira (a) o predicado das calculadoras do {@code core.finance} (totais) e (b) a contagem; a consulta da
 * página (SQL) repete a mesma regra campo a campo e um IT compara página e totais.
 *
 * <ul>
 *   <li>{@code accountId}: a conta aparece como origem <b>ou</b> como contrapartida (transferência e aplicação);</li>
 *   <li>{@code q}: contém, sem diferenciar maiúsculas, na descrição; acentos não são normalizados (S-71);</li>
 *   <li>{@code status}: padrão {@code ACTIVE}.</li>
 * </ul>
 *
 * @param from primeira data (inclusive); {@code null} = sem limite
 * @param to   última data (inclusive); {@code null} = sem limite
 */
public record TransactionFilter(LocalDate from, LocalDate to, TransactionType type, UUID accountId, UUID categoryId,
                                PaymentMethod method, TransactionStatus status, String q) {

    public static final int Q_MAX_LENGTH = 100;

    public TransactionFilter {
        if (from != null && to != null && from.isAfter(to)) {
            throw new IllegalArgumentException("from depois de to");
        }
        status = status == null ? TransactionStatus.ACTIVE : status;
        q = q == null || q.isBlank() ? null : q.strip();
    }

    /** O filtro casa com a movimentação inteira (data, atributos e status). */
    public boolean matches(Transaction transaction) {
        return inPeriod(transaction.date()) && attributes().test(transaction);
    }

    /**
     * Tudo menos o período: tipo, conta, categoria, método, texto e status. É o predicado das calculadoras, que já
     * recebem {@code from}/{@code to} à parte.
     */
    public Predicate<Transaction> attributes() {
        String needle = q == null ? null : q.toLowerCase(Locale.ROOT);
        return t -> t.status() == status
                && (type == null || t.type() == type)
                && (accountId == null || accountId.equals(t.accountId()) || accountId.equals(t.counterpartAccountId()))
                && (categoryId == null || categoryId.equals(t.categoryId()))
                && (method == null || t.paymentMethod() == method)
                && (needle == null || (t.description() != null && t.description().toLowerCase(Locale.ROOT).contains(needle)));
    }

    public boolean inPeriod(LocalDate date) {
        return (from == null || !date.isBefore(from)) && (to == null || !date.isAfter(to));
    }
}
