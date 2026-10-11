package com.moneylog.core.commitment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.moneylog.core.commitment.exception.CommitmentExceptions.InvalidCommitmentException;
import com.moneylog.core.commitment.exception.CommitmentExceptions.ScheduleItemAlreadyPaidException;
import com.moneylog.core.commitment.exception.CommitmentExceptions.ScheduleItemNotFoundException;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;

@DisplayName("Compromisso acompanhado: invariantes do cronograma e do empréstimo de terceiro (ADR-014)")
class TrackedCommitmentTest {

    private static final UUID USER = UUID.randomUUID();

    private static Money brl(String amount) {
        return Money.of(amount, CurrencyCode.BRL);
    }

    private static ScheduleItem item(int position, ScheduleItemType type, String due, String amount, boolean paid) {
        return new ScheduleItem(position, "Item " + position, type, due == null ? null : LocalDate.parse(due), brl(amount), paid, null);
    }

    private static List<ScheduleItem> sample() {
        return List.of(item(1, ScheduleItemType.DOWN_PAYMENT, null, "100.00", true), item(2, ScheduleItemType.INSTALLMENT, "2026-11-15", "200.00", true),
                item(3, ScheduleItemType.INSTALLMENT, "2026-12-15", "200.00", false), item(4, ScheduleItemType.EXTRAORDINARY, "2026-12-20", "300.00", false),
                item(5, ScheduleItemType.FINANCING, null, "1000.00", false));
    }

    @Test
    @DisplayName("projeto: pago = Σ itens pagos; saldo nominal = total − pago; financiamento fora do pendente direto; próximo é o mais cedo com data")
    void deveDerivarPagoSaldoEPendente() {
        TrackedCommitment c = TrackedCommitment.longTermProject(UUID.randomUUID(), USER, "Projeto", "Contraparte", null, brl("1800.00"), sample());
        assertThat(c.paid()).isEqualTo(brl("300.00"));
        assertThat(c.nominalTotal()).isEqualTo(brl("1800.00"));
        assertThat(c.nominalBalance()).isEqualTo(brl("1500.00"));
        assertThat(c.progress()).isEqualByComparingTo("0.1667");
        assertThat(c.directPending()).isEqualTo(brl("500.00"));
        assertThat(c.financingPending()).isEqualTo(brl("1000.00"));
        assertThat(c.nextDueDate()).isEqualTo(LocalDate.parse("2026-12-15"));
        assertThat(c.pendingByMonth()).containsEntry(YearMonth.of(2026, 12), brl("500.00")).hasSize(1);
    }

    @Test
    @DisplayName("marcar item como pago só muda o acompanhamento; marcar de novo é recusado; posição inexistente é 404")
    void deveMarcarItemComoPago() {
        TrackedCommitment c = TrackedCommitment.longTermProject(UUID.randomUUID(), USER, "Projeto", null, null, null, sample());
        c.markItemPaid(3, LocalDate.parse("2026-12-10"));
        assertThat(c.paid()).isEqualTo(brl("500.00"));
        assertThat(c.nextDueDate()).isEqualTo(LocalDate.parse("2026-12-20"));
        assertThatThrownBy(() -> c.markItemPaid(3, LocalDate.parse("2026-12-11"))).isInstanceOf(ScheduleItemAlreadyPaidException.class);
        assertThatThrownBy(() -> c.markItemPaid(99, LocalDate.parse("2026-12-11"))).isInstanceOf(ScheduleItemNotFoundException.class);
    }

    @Test
    @DisplayName("total que não fecha com Σ do cronograma, cronograma vazio e posições fora de sequência são recusados")
    void deveRecusarProjetoInvalido() {
        assertThatThrownBy(() -> TrackedCommitment.longTermProject(UUID.randomUUID(), USER, "P", null, null, brl("1799.99"), sample()))
                .isInstanceOf(InvalidCommitmentException.class);
        assertThatThrownBy(() -> TrackedCommitment.longTermProject(UUID.randomUUID(), USER, "P", null, null, null, List.of()))
                .isInstanceOf(InvalidCommitmentException.class);
        assertThatThrownBy(() -> TrackedCommitment.longTermProject(UUID.randomUUID(), USER, "P", null, null, null,
                List.of(item(2, ScheduleItemType.INSTALLMENT, "2026-11-15", "10.00", false)))).isInstanceOf(InvalidCommitmentException.class);
        assertThatThrownBy(() -> new ScheduleItem(1, "x", ScheduleItemType.INSTALLMENT, null, brl("1.00"), false, LocalDate.parse("2026-01-01")))
                .isInstanceOf(InvalidCommitmentException.class);
    }

    @Test
    @DisplayName("empréstimo de terceiro: o saldo restante NÃO é calculado (nem por proporção); restantes = total − pagas")
    void naoDeveCalcularSaldoDoEmprestimoDeTerceiro() {
        TrackedCommitment c = TrackedCommitment.trackedThirdParty(UUID.randomUUID(), USER, "Empréstimo", "Titular", null, 10, 3, brl("300.00"),
                LocalDate.parse("2026-11-10"));
        assertThat(c.remainingInstallments()).isEqualTo(7);
        assertThat(c.nominalBalance()).isNull();
        assertThat(c.nominalTotal()).isNull();
        assertThat(c.progress()).isNull();
        assertThat(c.paid()).isEqualTo(brl("300.00"));
        assertThat(c.nextDueDate()).isEqualTo(LocalDate.parse("2026-11-10"));
        assertThatThrownBy(() -> c.markItemPaid(1, LocalDate.parse("2026-11-10"))).isInstanceOf(InvalidCommitmentException.class);
        c.updateProgress(4, brl("400.00"), LocalDate.parse("2026-12-10"), "nota");
        assertThat(c.remainingInstallments()).isEqualTo(6);
        assertThatThrownBy(() -> c.updateProgress(11, null, null, null)).isInstanceOf(InvalidCommitmentException.class);
        assertThatThrownBy(() -> TrackedCommitment.trackedThirdParty(UUID.randomUUID(), USER, "E", null, null, 10, 11, null, null))
                .isInstanceOf(InvalidCommitmentException.class);
    }

    @Test
    @DisplayName("propriedade (300 cronogramas aleatórios): pago + pendente direto + financiamento = total; Σ pendente por mês = pendente direto; progresso em [0,1]")
    void propriedadeDeConservacao() {
        Random random = new Random(20261007);
        for (int round = 0; round < 300; round++) {
            int n = 1 + random.nextInt(30);
            List<ScheduleItem> items = new ArrayList<>();
            long total = 0;
            for (int i = 1; i <= n; i++) {
                ScheduleItemType type = ScheduleItemType.values()[random.nextInt(ScheduleItemType.values().length)];
                long cents = 1 + random.nextInt(5_000_000);
                total += cents;
                String due = type == ScheduleItemType.FINANCING && random.nextBoolean() ? null : LocalDate.of(2026, 1, 1).plusDays(random.nextInt(900)).toString();
                items.add(new ScheduleItem(i, "Item " + i, type, due == null ? null : LocalDate.parse(due), Money.ofCents(cents, CurrencyCode.BRL),
                        random.nextBoolean(), null));
            }
            TrackedCommitment c = TrackedCommitment.longTermProject(UUID.randomUUID(), USER, "P" + round, null, null,
                    Money.ofCents(total, CurrencyCode.BRL), items);
            Money byMonth = Money.sum(c.pendingByMonth().values(), CurrencyCode.BRL);
            long undatedDirect = items.stream().filter(i -> !i.isPaid() && i.type() != ScheduleItemType.FINANCING && i.dueDate() == null)
                    .mapToLong(i -> i.amount().cents()).sum();
            assertThat(c.paid().add(c.directPending()).add(c.financingPending())).isEqualTo(c.nominalTotal());
            assertThat(c.nominalBalance().add(c.paid())).isEqualTo(c.nominalTotal());
            assertThat(byMonth.cents() + undatedDirect).isEqualTo(c.directPending().cents());
            assertThat(c.progress().doubleValue()).isBetween(0.0, 1.0);
        }
    }
}
