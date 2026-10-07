package com.moneylog.core.finance.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.moneylog.core.sk.ledger.BookType;
import com.moneylog.core.sk.ledger.LedgerEntry;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.time.Competence;
import com.moneylog.core.transaction.domain.Transaction;
import com.moneylog.core.transaction.domain.TransactionSource;
import com.moneylog.core.transaction.domain.TransactionType;

/**
 * Tendência histórica opcional da projeção (S-152): <b>estimativa</b>, sempre separada da série base. É a média de 3 meses
 * <b>completos</b> anteriores ao corrente do <b>consumo novo</b>: despesas de conta não recorrentes + a 1ª parcela (ou a compra à
 * vista) no cartão. Nunca conta parcelas 2..N de compras antigas nem ocorrências de recorrência (que já estão nos compromissos),
 * então ligar a tendência não dobra nada. Reembolsos e consumo de dívida ficam de fora (conservador).
 *
 * <p>Cada um dos 3 meses precisa ter ao menos um lançamento de consumo novo (S-262): mês vazio no meio do histórico não vira média baixa,
 * vira {@code INSUFFICIENT_HISTORY}.
 *
 * @param available {@code false} com menos de 3 meses completos de histórico ou com algum deles sem consumo novo (não inventa média)
 * @param reason    {@code INSUFFICIENT_HISTORY} quando indisponível
 * @param average   média mensal ({@code null} se indisponível)
 * @param months    os 3 meses usados (vazio se indisponível)
 */
public record SpendingTrend(boolean available, String reason, Money average, List<Competence> months) {

    public static final String INSUFFICIENT_HISTORY = "INSUFFICIENT_HISTORY";
    public static final int MONTHS = 3;

    public static SpendingTrend of(FinancialRecords records, Ledger ledger, LocalDate today) {
        Competence current = Competence.from(today);
        Competence oldest = current.minusMonths(MONTHS);
        LocalDate earliest = records.transactions().stream().filter(Transaction::isActive).map(Transaction::date)
                .min(LocalDate::compareTo).orElse(null);
        // os 3 meses só são "completos" se o histórico começa no 1º dia do mais antigo ou antes (senão o mês inicial é parcial)
        if (earliest == null || earliest.isAfter(oldest.firstDay())) {
            return new SpendingTrend(false, INSUFFICIENT_HISTORY, null, List.of());
        }
        Map<UUID, Transaction> transactions = records.transactionsById();
        List<Competence> months = List.of(oldest, oldest.plusMonths(1), oldest.plusMonths(2));
        Money total = Money.zero(CurrencyCode.BRL);
        for (Competence month : months) {
            int entries = 0;
            for (LedgerEntry e : ledger.inCompetence(BookType.EXPENSE, month)) {
                Transaction tx = transactions.get(e.source().transactionId());
                if (tx != null && isNewConsumption(tx, e)) {
                    total = total.add(e.posting().amount());
                    entries++;
                }
            }
            if (entries == 0) {
                // S-262 (M3b): um único lançamento antigo bastava para "histórico completo", com meses vazios no meio e média falsamente baixa
                return new SpendingTrend(false, INSUFFICIENT_HISTORY, null, List.of());
            }
        }
        long cents = BigDecimal.valueOf(total.cents()).divide(BigDecimal.valueOf(MONTHS), 0, RoundingMode.HALF_UP).longValueExact();
        return new SpendingTrend(true, null, Money.ofCents(cents, CurrencyCode.BRL), months);
    }

    private static boolean isNewConsumption(Transaction tx, LedgerEntry entry) {
        if (tx.origin().source() == TransactionSource.RECURRENCE) {
            return false;
        }
        if (tx.type() == TransactionType.EXPENSE) {
            return true;
        }
        if (tx.type() != TransactionType.CARD_PURCHASE) {
            return false;
        }
        UUID installmentId = entry.source().installmentId();
        return installmentId == null || tx.installments().stream()
                .anyMatch(i -> i.id().equals(installmentId) && i.number() == 1);
    }
}
