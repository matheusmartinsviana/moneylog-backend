package com.moneylog.core.finance.domain;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.moneylog.core.sk.ledger.BookType;
import com.moneylog.core.sk.ledger.LedgerEntry;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;

/**
 * Série de entradas e saídas por período (redesign, S-283): <b>leitura do razão ativo</b> nos livros {@code INCOME} (receita, sinal
 * invertido) e {@code EXPENSE} (consumo), agrupada pela {@code effectiveDate} do posting em {@code DAY}/{@code WEEK} e pela <b>competência</b> do posting em {@code MONTH}
 * (a compra de 1.200 em 6x tem 200 em cada mês, nunca 1.200 num dia só). É irmã de {@link ConsumptionReport} (que agrupa por competência) e <b>não tem
 * classificação própria</b>: cada posting dos dois livros entra uma vez, exatamente como entra no {@code month.income} e no
 * {@code month.consumption} do dashboard, no {@code EntryReport} e no {@code MonthlySummary} (PR 01, regra 10). A competência de um posting é o mês da data efetiva,
 * <b>exceto na receita com {@code incomeCompetence}</b> (ADR-017: renda de outubro recebida em setembro). Por isso {@code DAY}/{@code WEEK}
 * (quando a renda <b>entrou</b>, o caixa) e {@code MONTH} (a renda <b>do mês</b>, igual ao resumo e ao dashboard) só coincidem num mês civil
 * quando nenhuma receita muda de mês; o consumo é igual nos dois. Somando todos os meses (ou todos os dias), a renda total é a mesma:
 * mover a competência só troca o balde, nunca cria nem perde centavo.
 *
 * <p>Fora dos dois livros, logo fora da série: pagamento de fatura, transferência, aplicação e resgate, ajuste de saldo, empréstimo
 * concedido ou recebido, quitação de dívida, estornadas (postings inativos) e saldo inicial. Reembolso abate na categoria original (valor
 * negativo no livro {@code EXPENSE}: um dia só com reembolso tem {@code expenses} negativo) e o consumo de dívida entra.
 *
 * <p>{@code DAY} e {@code WEEK} nunca passam de hoje (lançamento datado depois de hoje não aparece); o ponto {@code MONTH} do mês corrente
 * é a competência inteira, igual ao dashboard, e leva {@code partial}. {@code net} = {@code income} − {@code expenses}: é o resultado do
 * período, não a variação do saldo.
 */
public record IncomeExpenseSeries(ReportPeriod period, LocalDate from, LocalDate to, Basis basis, Granularity granularity,
                                  List<Point> points, Totals totals) {

    /** Rótulo da base de agrupamento: {@code DATE} (dia/semana) pela data efetiva (caixa); {@code COMPETENCE} (mês) pela competência do posting. */
    public enum Basis { DATE, COMPETENCE }

    public enum Granularity { DAY, WEEK, MONTH }

    /** Um balde da série; {@code partial} = contém hoje (ainda não terminou). */
    public record Point(LocalDate periodStart, LocalDate periodEnd, boolean partial, Money income, Money expenses, Money net) {
    }

    public record Totals(Money income, Money expenses, Money net) {
    }

    public static IncomeExpenseSeries of(Ledger ledger, ReportPeriod period, LocalDate today) {
        LocalDate from = period.from(today);
        LocalDate to = period.to(today);
        List<LocalDate[]> buckets = buckets(period, from, to);
        TreeMap<LocalDate, Integer> indexByStart = new TreeMap<>();
        long[] income = new long[buckets.size()];
        long[] expenses = new long[buckets.size()];
        for (int i = 0; i < buckets.size(); i++) {
            indexByStart.put(buckets.get(i)[0], i);
        }
        // DAY e WEEK terminam hoje; MONTH é a competência inteira (o dashboard também não corta o mês em hoje)
        LocalDate last = to;
        for (LedgerEntry entry : ledger.entries()) {
            BookType book = entry.posting().bookType();
            if (book != BookType.INCOME && book != BookType.EXPENSE) {
                continue;
            }
            // MONTH agrupa pela competência do posting (a renda pode pertencer a outro mês, ADR-017); DAY e WEEK são caixa: data efetiva
            LocalDate date = period.granularity() == Granularity.MONTH ? entry.posting().competence().firstDay()
                    : entry.posting().effectiveDate();
            if (date.isBefore(from) || date.isAfter(last)) {
                continue;
            }
            Map.Entry<LocalDate, Integer> floor = indexByStart.floorEntry(date);
            int index = floor.getValue();
            long cents = entry.posting().amount().cents();
            if (book == BookType.INCOME) {
                income[index] = Math.subtractExact(income[index], cents);
            } else {
                expenses[index] = Math.addExact(expenses[index], cents);
            }
        }
        Money zero = Money.zero(CurrencyCode.BRL);
        Money totalIncome = zero;
        Money totalExpenses = zero;
        List<Point> points = new ArrayList<>(buckets.size());
        for (int i = 0; i < buckets.size(); i++) {
            LocalDate start = buckets.get(i)[0];
            LocalDate end = buckets.get(i)[1];
            Money in = Money.ofCents(income[i], CurrencyCode.BRL);
            Money out = Money.ofCents(expenses[i], CurrencyCode.BRL);
            points.add(new Point(start, end, !today.isBefore(start) && !today.isAfter(end), in, out, in.subtract(out)));
            totalIncome = totalIncome.add(in);
            totalExpenses = totalExpenses.add(out);
        }
        Granularity granularity = period.granularity();
        return new IncomeExpenseSeries(period, from, to, granularity == Granularity.MONTH ? Basis.COMPETENCE : Basis.DATE, granularity,
                List.copyOf(points), new Totals(totalIncome, totalExpenses, totalIncome.subtract(totalExpenses)));
    }

    /** Baldes densos (zeros incluídos) cobrindo exatamente {@code [from, to]}, cada um recortado na janela. */
    private static List<LocalDate[]> buckets(ReportPeriod period, LocalDate from, LocalDate to) {
        List<LocalDate[]> buckets = new ArrayList<>();
        switch (period.granularity()) {
            case DAY -> {
                for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
                    buckets.add(new LocalDate[] {day, day});
                }
            }
            case WEEK -> {
                LocalDate start = from;
                while (!start.isAfter(to)) {
                    LocalDate sunday = ReportPeriod.mondayOf(start).plusDays(6);
                    LocalDate end = sunday.isAfter(to) ? to : sunday;
                    buckets.add(new LocalDate[] {start, end});
                    start = end.plusDays(1);
                }
            }
            case MONTH -> {
                for (LocalDate month = from; !month.isAfter(to); month = month.plusMonths(1)) {
                    buckets.add(new LocalDate[] {month, month.withDayOfMonth(month.lengthOfMonth())});
                }
            }
        }
        return buckets;
    }
}
