package com.moneylog.core.finance.domain;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.moneylog.core.debt.domain.Debt;
import com.moneylog.core.debt.domain.DebtDirection;
import com.moneylog.core.debt.domain.DebtStatus;
import com.moneylog.core.sk.ledger.BookType;
import com.moneylog.core.sk.ledger.LedgerEntry;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.time.Competence;
import com.moneylog.core.thirdparty.domain.ThirdPartyExpense;
import com.moneylog.core.transaction.domain.Transaction;
import com.moneylog.core.transaction.domain.TransactionType;

/**
 * "Dinheiro a receber de terceiros" (irmã, mãe, amigos). Lê o <b>estado</b>, nunca soma movimentos, e fica fora de patrimônio, renda e
 * consumo. Duas fontes, sem sobreposição:
 * <ul>
 *   <li><b>Dívidas a receber</b> ({@code RECEIVABLE}: empréstimo concedido): a receber = principal, recebido = Σ quitações ativas;</li>
 *   <li><b>Despesas de terceiro</b> (compra/despesa marcada com o responsável): a receber = valor cobrado da compra, recebido = Σ
 *       {@code REFUND} ativos ligados a ela ({@code refundOf}); o reembolso reduz o consumo na categoria original, nunca é receita.</li>
 * </ul>
 * Regra do gasto (decisão do dono): a despesa de terceiro <b>continua consumo bruto do usuário até o reembolso</b>; o recebimento a
 * neutraliza. O gasto pessoal líquido do mês é o consumo do livro (já abatido dos reembolsos); {@link MonthView#thirdPartyPending()} é o
 * que ainda está bruto e é de terceiro.
 */
public final class ThirdPartyReceivables {

    private ThirdPartyReceivables() {
    }

    public enum Source {
        DEBT, EXPENSE, RECEIPT
    }

    public record Item(Source source, UUID refId, String description, LocalDate date, LocalDate expectedDate, Money owed, Money received,
                       Money pending, String status) {
    }

    public record PersonLine(UUID personId, String name, Money owed, Money received, Money pending, List<Item> items) {
    }

    /**
     * @param grossConsumption     consumo bruto da competência: despesas pagas + cartão + dívidas de consumo (antes dos reembolsos)
     * @param refunds              todos os reembolsos da competência
     * @param personalNet          gasto pessoal líquido = consumo do livro = bruto − reembolsos
     * @param thirdPartyConsumption consumo bruto da competência que é de terceiro (despesas marcadas)
     * @param thirdPartyReimbursed reembolsos da competência ligados a despesas de terceiro
     * @param thirdPartyPending    {@code thirdPartyConsumption − thirdPartyReimbursed}: de terceiro e ainda não reembolsado (dentro do bruto)
     */
    public record MonthView(Competence competence, Money grossConsumption, Money refunds, Money personalNet,
                            Money thirdPartyConsumption, Money thirdPartyReimbursed, Money thirdPartyPending) {
    }

    public record Overview(LocalDate asOf, Money owed, Money received, Money pending, List<PersonLine> people, MonthView month) {
    }

    public static Overview of(FinancialRecords records, List<ThirdPartyExpense> marks, Map<UUID, String> personNames, LocalDate today,
                              Competence competence) {
        return of(records, marks, List.of(), personNames, today, competence);
    }

    /**
     * @param receipts recebimentos já refletidos nos saldos, sem movimento de conta: os ligados a uma despesa marcada reduzem o pendente dela;
     *                 os sem vínculo viram um item {@code RECEIPT} já recebido (a receber = recebido, pendente 0), "origem a confirmar"
     */
    public static Overview of(FinancialRecords records, List<ThirdPartyExpense> marks, List<com.moneylog.core.thirdparty.domain.ThirdPartyReceipt> receipts,
                              Map<UUID, String> personNames, LocalDate today, Competence competence) {
        Money zero = Money.zero(CurrencyCode.BRL);
        Map<UUID, Transaction> txs = records.transactionsById();
        Map<UUID, List<Item>> byPerson = new LinkedHashMap<>();
        // dívidas a receber
        for (Debt d : records.debts()) {
            if (d.direction() != DebtDirection.RECEIVABLE || d.personId() == null
                    || d.status() == DebtStatus.CANCELED || d.status() == DebtStatus.VOIDED) {
                continue;
            }
            byPerson.computeIfAbsent(d.personId(), k -> new ArrayList<>()).add(new Item(Source.DEBT, d.id(), d.description(), d.date(),
                    d.expectedDate(), d.principal(), d.settled(), d.outstanding(), d.status().name()));
        }
        // despesas de terceiro
        Set<UUID> markedIds = marks.stream().map(ThirdPartyExpense::transactionId).collect(Collectors.toSet());
        for (ThirdPartyExpense mark : marks) {
            Transaction tx = txs.get(mark.transactionId());
            if (tx == null || !tx.isActive()) {
                continue;
            }
            Money owed = mark.amount() != null ? mark.amount() : tx.chargedAmount();
            Money received = refundsOf(records, tx.id()).add(Money.sum(receipts.stream()
                    .filter(r -> tx.id().equals(r.transactionId())).map(com.moneylog.core.thirdparty.domain.ThirdPartyReceipt::amount).toList(), CurrencyCode.BRL));
            Money pending = received.isLessThan(owed) ? owed.subtract(received) : zero;
            byPerson.computeIfAbsent(mark.personId(), k -> new ArrayList<>()).add(new Item(Source.EXPENSE, tx.id(), tx.description(),
                    tx.date(), null, owed, received, pending, pending.isZero() ? "RECEIVED" : received.isZero() ? "PENDING" : "PARTIAL"));
        }
        for (com.moneylog.core.thirdparty.domain.ThirdPartyReceipt r : receipts) {
            if (r.transactionId() == null) {
                byPerson.computeIfAbsent(r.personId(), k -> new ArrayList<>()).add(new Item(Source.RECEIPT, r.id(), r.note(),
                        r.receivedOn() == null ? today : r.receivedOn(), null, r.amount(), r.amount(), zero, "RECEIVED"));
            }
        }
        List<PersonLine> lines = new ArrayList<>();
        Money owedT = zero;
        Money receivedT = zero;
        Money pendingT = zero;
        for (Map.Entry<UUID, List<Item>> e : byPerson.entrySet()) {
            Money o = zero;
            Money r = zero;
            Money p = zero;
            List<Item> items = e.getValue().stream().sorted(Comparator.comparing(Item::date).thenComparing(Item::refId)).toList();
            for (Item i : items) {
                o = o.add(i.owed());
                r = r.add(i.received());
                p = p.add(i.pending());
            }
            lines.add(new PersonLine(e.getKey(), personNames.getOrDefault(e.getKey(), ""), o, r, p, items));
            owedT = owedT.add(o);
            receivedT = receivedT.add(r);
            pendingT = pendingT.add(p);
        }
        lines.sort(Comparator.comparing(PersonLine::name));
        return new Overview(today, owedT, receivedT, pendingT, List.copyOf(lines), month(records, markedIds, competence));
    }

    private static Money refundsOf(FinancialRecords records, UUID originalId) {
        return Money.sum(records.transactions().stream()
                .filter(t -> t.isActive() && t.type() == TransactionType.REFUND && originalId.equals(t.refundOfId()))
                .map(Transaction::amount).toList(), CurrencyCode.BRL);
    }

    private static MonthView month(FinancialRecords records, Set<UUID> markedIds, Competence competence) {
        Money zero = Money.zero(CurrencyCode.BRL);
        ConsumptionReport report = ConsumptionReport.of(records, records.ledger(), competence);
        Money gross = report.expensesPaid().add(report.credit()).add(report.debtConsumption());
        Money thirdConsumption = zero;
        for (LedgerEntry e : records.ledger().inCompetence(BookType.EXPENSE, competence)) {
            if (e.source().transactionId() != null && markedIds.contains(e.source().transactionId())) {
                thirdConsumption = thirdConsumption.add(e.posting().amount());
            }
        }
        Money reimbursed = zero;
        for (Transaction t : records.transactions()) {
            if (t.isActive() && t.type() == TransactionType.REFUND && t.refundOfId() != null && markedIds.contains(t.refundOfId())
                    && competence.contains(t.date())) {
                reimbursed = reimbursed.add(t.amount());
            }
        }
        Money pending = reimbursed.isLessThan(thirdConsumption) ? thirdConsumption.subtract(reimbursed) : zero;
        return new MonthView(competence, gross, report.refunds(), report.consumption(), thirdConsumption, reimbursed, pending);
    }
}
