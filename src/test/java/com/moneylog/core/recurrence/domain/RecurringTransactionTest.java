package com.moneylog.core.recurrence.domain;

import static com.moneylog.core.account.util.factory.AccountTestFactory.NUBANK_ID;
import static com.moneylog.core.account.util.factory.AccountTestFactory.umaContaCorrente;
import static com.moneylog.core.account.util.factory.AccountTestFactory.umaContaSemSaldo;
import static com.moneylog.core.card.util.factory.CreditCardTestFactory.ROXINHO_ID;
import static com.moneylog.core.card.util.factory.CreditCardTestFactory.umCartao;
import static com.moneylog.core.category.util.factory.CategoryTestFactory.ALIMENTACAO_ID;
import static com.moneylog.core.category.util.factory.CategoryTestFactory.SALARIO_ID;
import static com.moneylog.core.category.util.factory.CategoryTestFactory.umaCategoriaDeDespesa;
import static com.moneylog.core.category.util.factory.CategoryTestFactory.umaCategoriaDeReceita;
import static com.moneylog.core.util.Ids.USER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.moneylog.core.card.domain.InvoiceChain;
import com.moneylog.core.recurrence.exception.RecurrenceExceptions.InvalidRecurrenceException;
import com.moneylog.core.recurrence.exception.RecurrenceExceptions.NotAnOccurrenceException;
import com.moneylog.core.recurrence.exception.RecurrenceExceptions.OccurrenceAlreadyRealizedException;
import com.moneylog.core.recurrence.exception.RecurrenceExceptions.OccurrenceSkippedException;
import com.moneylog.core.recurrence.exception.RecurrenceExceptions.RecurrenceEndedException;
import com.moneylog.core.recurrence.exception.RecurrenceExceptions.RecurrenceInactiveException;
import com.moneylog.core.recurrence.exception.RecurrenceExceptions.RecurrenceReferenceMismatchException;
import com.moneylog.core.sk.money.FlowDirection;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.transaction.domain.PaymentMethod;
import com.moneylog.core.transaction.domain.Transaction;
import com.moneylog.core.transaction.domain.TransactionSource;
import com.moneylog.core.transaction.domain.TransactionType;

@DisplayName("Recorrência (T9)")
class RecurringTransactionTest {

    private static final UUID REC_ID = UUID.fromString("00000000-0000-0000-0000-0000000c0c01");
    private static final UUID TX_ID = UUID.fromString("00000000-0000-0000-0000-0000000c0c02");

    private static LocalDate d(String iso) {
        return LocalDate.parse(iso);
    }

    private static RecurringTransaction umaAssinatura(Schedule schedule) {
        return RecurringTransaction.ofAccount(REC_ID, USER_ID, TransactionType.EXPENSE, Money.of("119.00"), "Streaming",
                ALIMENTACAO_ID, NUBANK_ID, PaymentMethod.DEBIT, schedule);
    }

    private static List<LocalDate> datas(RecurringTransaction r, String from, String to) {
        return r.occurrences(d(from), d(to)).stream().map(Occurrence::date).toList();
    }

    @Test
    @DisplayName("CA1: mensal no dia 31 a partir de 31/01/2027 até 30/04/2027 = 31/01, 28/02, 31/03, 30/04 (a partir da âncora)")
    void deveGerarMensalNoDia31() {
        assertThat(datas(umaAssinatura(Schedule.of(Frequency.MONTHLY, d("2027-01-31"))), "2027-01-01", "2027-04-30"))
                .containsExactly(d("2027-01-31"), d("2027-02-28"), d("2027-03-31"), d("2027-04-30"));
    }

    static Stream<Arguments> frequencias() {
        return Stream.of(
                Arguments.of(Schedule.of(Frequency.DAILY, d("2026-12-30")), "2026-12-30", "2027-01-02",
                        List.of("2026-12-30", "2026-12-31", "2027-01-01", "2027-01-02")),
                Arguments.of(Schedule.of(Frequency.WEEKLY, d("2026-10-05")), "2026-10-01", "2026-10-31",
                        List.of("2026-10-05", "2026-10-12", "2026-10-19", "2026-10-26")),
                Arguments.of(Schedule.of(Frequency.YEARLY, d("2028-02-29")), "2028-01-01", "2032-12-31",
                        List.of("2028-02-29", "2029-02-28", "2030-02-28", "2031-02-28", "2032-02-29")),
                Arguments.of(Schedule.custom(2, IntervalUnit.WEEKS, d("2026-10-01")), "2026-10-01", "2026-11-15",
                        List.of("2026-10-01", "2026-10-15", "2026-10-29", "2026-11-12")),
                Arguments.of(Schedule.custom(3, IntervalUnit.MONTHS, d("2026-11-30")), "2026-11-01", "2027-08-31",
                        List.of("2026-11-30", "2027-02-28", "2027-05-30", "2027-08-30")),
                Arguments.of(Schedule.custom(10, IntervalUnit.DAYS, d("2026-10-01")), "2026-10-05", "2026-10-31",
                        List.of("2026-10-11", "2026-10-21", "2026-10-31")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("frequencias")
    @DisplayName("CA2: DAILY, WEEKLY, YEARLY (29/02 vira 28/02) e CUSTOM batem com casos fixos")
    void deveGerarAsFrequencias(Schedule schedule, String from, String to, List<String> esperadas) {
        assertThat(datas(umaAssinatura(schedule), from, to)).containsExactlyElementsOf(esperadas.stream().map(LocalDate::parse).toList());
    }

    @Test
    @DisplayName("CA3: nenhuma ocorrência depois de endDate ou de maxOccurrences")
    void deveRespeitarOsLimites() {
        Schedule mensal = Schedule.of(Frequency.MONTHLY, d("2026-10-10"));
        assertThat(datas(umaAssinatura(mensal.until(d("2026-12-10"))), "2026-01-01", "2027-12-31"))
                .containsExactly(d("2026-10-10"), d("2026-11-10"), d("2026-12-10"));
        assertThat(datas(umaAssinatura(mensal.limitedTo(2)), "2026-01-01", "2027-12-31"))
                .containsExactly(d("2026-10-10"), d("2026-11-10"));
        assertThat(datas(umaAssinatura(mensal.limitedTo(2)), "2026-11-01", "2027-12-31")).containsExactly(d("2026-11-10"));
    }

    @Test
    @DisplayName("CA4: realizar de novo lança OccurrenceAlreadyRealizedException; pulada não aparece mais como prevista")
    void deveRealizarUmaVezEPular() {
        RecurringTransaction r = umaAssinatura(Schedule.of(Frequency.MONTHLY, d("2026-10-10")));

        Transaction tx = r.realize(d("2026-10-10"), TX_ID, null, umaContaCorrente(), umaCategoriaDeDespesa());
        assertThat(tx.origin().source()).isEqualTo(TransactionSource.RECURRENCE);
        assertThat(tx.origin().recurrenceId()).isEqualTo(REC_ID);
        assertThat(tx.origin().occurrenceDate()).isEqualTo(d("2026-10-10"));
        assertThat(tx.amount()).isEqualTo(Money.of("119.00"));
        assertThatThrownBy(() -> r.realize(d("2026-10-10"), TX_ID, null, umaContaCorrente(), umaCategoriaDeDespesa()))
                .isInstanceOf(OccurrenceAlreadyRealizedException.class);

        r.skip(d("2026-11-10"));
        assertThat(datas(r, "2026-10-01", "2026-12-31")).containsExactly(d("2026-12-10"));
        assertThatThrownBy(() -> r.realize(d("2026-11-10"), TX_ID, null, umaContaCorrente(), umaCategoriaDeDespesa()))
                .isInstanceOf(OccurrenceSkippedException.class);
        assertThatThrownBy(() -> r.skip(d("2026-11-11"))).isInstanceOf(NotAnOccurrenceException.class);
        assertThatThrownBy(() -> r.skip(null)).isInstanceOf(NotAnOccurrenceException.class);
        r.markRealized(d("2026-12-10"));
        assertThat(datas(r, "2026-10-01", "2026-12-31")).isEmpty();
    }

    @Test
    @DisplayName("CA5: ocorrência prevista é compromisso (direção e valor) e não gera posting; nada é realizado sozinho")
    void naoDeveGerarPostingNaPrevisao() {
        RecurringTransaction r = umaAssinatura(Schedule.of(Frequency.MONTHLY, d("2026-10-10")));
        Occurrence o = r.occurrences(d("2026-10-01"), d("2026-10-31")).getFirst();
        assertThat(o.direction()).isEqualTo(FlowDirection.OUT);
        assertThat(o.amount()).isEqualTo(Money.of("119.00"));
        assertThat(Occurrence.class.getRecordComponents()).noneMatch(c -> c.getName().contains("posting"));
        assertThat(r.occurrences(d("2026-10-01"), d("2026-10-31"))).hasSize(1);
    }

    @Test
    @DisplayName("CA6: semanal de 50 + mensal de 119 = 50 × 52/12 + 119 = 335,67 (HALF_EVEN), rotulado como estimativa")
    void deveEstimarOCustoFixoMensal() {
        RecurringTransaction semanal = RecurringTransaction.ofAccount(UUID.randomUUID(), USER_ID, TransactionType.EXPENSE,
                Money.of("50.00"), "Feira", ALIMENTACAO_ID, NUBANK_ID, PaymentMethod.PIX, Schedule.of(Frequency.WEEKLY, d("2026-10-05")));
        RecurringTransaction mensal = umaAssinatura(Schedule.of(Frequency.MONTHLY, d("2026-10-10")));
        RecurringTransaction salario = RecurringTransaction.ofAccount(UUID.randomUUID(), USER_ID, TransactionType.INCOME,
                Money.of("12000.00"), "Salário anual", SALARIO_ID, NUBANK_ID, PaymentMethod.TRANSFER, Schedule.of(Frequency.YEARLY, d("2026-12-20")));
        RecurringTransaction inativa = umaAssinatura(Schedule.of(Frequency.DAILY, d("2026-10-10")));
        inativa.deactivate();

        MonthlyEstimate estimate = MonthlyEstimate.of(List.of(semanal, mensal, salario, inativa), d("2026-10-10"));

        assertThat(estimate.fixedCosts()).isEqualTo(Money.of("335.67"));
        assertThat(estimate.recurringIncome()).isEqualTo(Money.of("1000.00"));
        assertThat(estimate.estimate()).isTrue();
        assertThat(inativa.occurrences(d("2026-10-01"), d("2026-10-31"))).isEmpty();
        assertThat(MonthlyEstimate.of(List.of(umaAssinatura(Schedule.custom(2, IntervalUnit.MONTHS, d("2026-10-10"))),
                umaAssinatura(Schedule.of(Frequency.DAILY, d("2026-10-10")))), d("2026-10-10")).fixedCosts())
                .isEqualTo(Money.of("3679.08"));
    }

    @Test
    @DisplayName("realização de receita e de compra no cartão; valor real informado; referências conferidas")
    void deveRealizarOsTiposDoModelo() {
        RecurringTransaction receita = RecurringTransaction.ofAccount(REC_ID, USER_ID, TransactionType.INCOME, Money.of("100.00"), "Aluguel",
                SALARIO_ID, NUBANK_ID, PaymentMethod.PIX, Schedule.of(Frequency.MONTHLY, d("2026-10-05")));
        assertThat(receita.direction()).isEqualTo(FlowDirection.IN);
        assertThat(receita.realize(d("2026-10-05"), TX_ID, Money.of("90.00"), umaContaCorrente(), umaCategoriaDeReceita()).amount())
                .isEqualTo(Money.of("90.00"));

        RecurringTransaction cartao = RecurringTransaction.ofCard(REC_ID, USER_ID, Money.of("39.90"), "Música", ALIMENTACAO_ID,
                ROXINHO_ID, Schedule.of(Frequency.MONTHLY, d("2026-10-15")));
        Transaction compra = cartao.realizeOnCard(d("2026-10-15"), TX_ID, null, umCartao(), InvoiceChain.empty(umCartao()),
                d("2026-10-15"), umaCategoriaDeDespesa());
        assertThat(compra.type()).isEqualTo(TransactionType.CARD_PURCHASE);
        assertThat(compra.installments()).hasSize(1);
        assertThat(cartao.cardId()).isEqualTo(ROXINHO_ID);

        assertThatThrownBy(() -> cartao.realizeOnCard(d("2026-11-15"), TX_ID, null, null, InvoiceChain.empty(umCartao()),
                d("2026-11-15"), umaCategoriaDeDespesa())).isInstanceOf(RecurrenceReferenceMismatchException.class);
        assertThatThrownBy(() -> cartao.realize(d("2026-11-15"), TX_ID, null, umaContaCorrente(), umaCategoriaDeDespesa()))
                .as("compra no cartão não é realizada como despesa em conta").isInstanceOf(RecurrenceReferenceMismatchException.class);
        RecurringTransaction r = umaAssinatura(Schedule.of(Frequency.MONTHLY, d("2026-10-10")));
        assertThatThrownBy(() -> r.realizeOnCard(d("2026-10-10"), TX_ID, null, umCartao(), InvoiceChain.empty(umCartao()),
                d("2026-10-10"), umaCategoriaDeDespesa())).as("despesa em conta não é realizada no cartão")
                .isInstanceOf(RecurrenceReferenceMismatchException.class);
        assertThatThrownBy(() -> r.realize(d("2026-10-10"), TX_ID, null, umaContaSemSaldo(), umaCategoriaDeDespesa()))
                .isInstanceOf(RecurrenceReferenceMismatchException.class);
        assertThatThrownBy(() -> r.realize(d("2026-10-10"), TX_ID, null, umaContaCorrente(), umaCategoriaDeReceita()))
                .isInstanceOf(RecurrenceReferenceMismatchException.class);
        assertThat(r.id()).isEqualTo(REC_ID);
        assertThat(r.userId()).isEqualTo(USER_ID);
        assertThat(r.description()).isEqualTo("Streaming");
        assertThat(r.categoryId()).isEqualTo(ALIMENTACAO_ID);
        assertThat(r.accountId()).isEqualTo(NUBANK_ID);
        assertThat(r.paymentMethod()).isEqualTo(PaymentMethod.DEBIT);
        assertThat(r.type()).isEqualTo(TransactionType.EXPENSE);
        assertThat(r.isActive()).isTrue();
    }

    @Test
    @DisplayName("validações do modelo e da agenda")
    void deveValidar() {
        Schedule s = Schedule.of(Frequency.MONTHLY, d("2026-10-10"));
        assertThatThrownBy(() -> RecurringTransaction.ofAccount(REC_ID, USER_ID, TransactionType.TRANSFER, Money.of("1.00"), null,
                ALIMENTACAO_ID, NUBANK_ID, PaymentMethod.PIX, s)).isInstanceOf(InvalidRecurrenceException.class);
        assertThatThrownBy(() -> RecurringTransaction.ofAccount(REC_ID, USER_ID, TransactionType.EXPENSE, Money.of("0.00"), null,
                ALIMENTACAO_ID, NUBANK_ID, PaymentMethod.PIX, s)).isInstanceOf(InvalidRecurrenceException.class);
        assertThatThrownBy(() -> RecurringTransaction.ofAccount(REC_ID, USER_ID, TransactionType.EXPENSE, Money.of("1.00"), null,
                ALIMENTACAO_ID, NUBANK_ID, null, s)).isInstanceOf(InvalidRecurrenceException.class);
        assertThatThrownBy(() -> RecurringTransaction.ofAccount(REC_ID, USER_ID, TransactionType.CARD_PURCHASE, Money.of("1.00"), null,
                ALIMENTACAO_ID, NUBANK_ID, null, s)).isInstanceOf(InvalidRecurrenceException.class);
        assertThatThrownBy(() -> RecurringTransaction.ofAccount(null, USER_ID, TransactionType.EXPENSE, Money.of("1.00"), null,
                ALIMENTACAO_ID, NUBANK_ID, PaymentMethod.PIX, s)).isInstanceOf(InvalidRecurrenceException.class);
        assertThatThrownBy(() -> Schedule.of(null, d("2026-10-10"))).isInstanceOf(InvalidRecurrenceException.class);
        assertThatThrownBy(() -> Schedule.custom(0, IntervalUnit.DAYS, d("2026-10-10"))).isInstanceOf(InvalidRecurrenceException.class);
        assertThatThrownBy(() -> Schedule.custom(1, null, d("2026-10-10"))).isInstanceOf(InvalidRecurrenceException.class);
        assertThatThrownBy(() -> new Schedule(Frequency.MONTHLY, 2, null, d("2026-10-10"), null, null)).isInstanceOf(InvalidRecurrenceException.class);
        assertThatThrownBy(() -> s.until(d("2026-10-09"))).isInstanceOf(InvalidRecurrenceException.class);
        assertThatThrownBy(() -> s.limitedTo(0)).isInstanceOf(InvalidRecurrenceException.class);
    }

    @Test
    @DisplayName("S-235: encerrada = sem ocorrência hoje nem depois (endDate anterior a hoje, ou quantidade esgotada); a de hoje e a sem fim não são; não depende de active")
    void deveDerivarOEncerramento() {
        LocalDate today = d("2026-10-10");
        RecurringTransaction semFim = umaAssinatura(Schedule.of(Frequency.MONTHLY, d("2026-01-10")));
        assertThat(semFim.isEnded(today)).isFalse();
        RecurringTransaction fimPassado = umaAssinatura(Schedule.of(Frequency.MONTHLY, d("2026-01-10")).until(d("2026-03-10")));
        assertThat(fimPassado.isEnded(today)).isTrue();
        RecurringTransaction fimHoje = umaAssinatura(Schedule.of(Frequency.MONTHLY, d("2026-01-10")).until(today));
        assertThat(fimHoje.isEnded(today)).isFalse();
        assertThat(fimHoje.isEnded(today.plusDays(1))).isTrue();
        RecurringTransaction fimEntre = umaAssinatura(Schedule.of(Frequency.MONTHLY, d("2026-01-10")).until(d("2026-11-09")));
        assertThat(fimEntre.isEnded(d("2026-10-11"))).isTrue();
        assertThat(fimEntre.isEnded(today)).isFalse();
        RecurringTransaction esgotada = umaAssinatura(Schedule.of(Frequency.MONTHLY, d("2026-01-10")).limitedTo(3));
        assertThat(esgotada.isEnded(today)).isTrue();
        assertThat(umaAssinatura(Schedule.of(Frequency.MONTHLY, d("2026-09-10")).limitedTo(3)).isEnded(today)).isFalse();
        assertThat(umaAssinatura(Schedule.of(Frequency.MONTHLY, d("2027-01-10")).until(d("2027-06-10"))).isEnded(today)).isFalse();
        semFim.deactivate();
        assertThat(semFim.isEnded(today)).isFalse();
        assertThat(Schedule.of(Frequency.DAILY, d("2026-01-01")).hasOccurrenceOnOrAfter(d("2099-01-01"))).isTrue();
    }

    @Test
    @DisplayName("S-235: o custo fixo e a estimativa por item ignoram a recorrência encerrada (endDate passada ou quantidade esgotada) e voltam a contar até o dia da última ocorrência")
    void naoDeveSomarEncerradaNaEstimativa() {
        LocalDate today = d("2026-10-10");
        RecurringTransaction encerrada = umaAssinatura(Schedule.of(Frequency.MONTHLY, d("2026-01-10")).until(d("2026-03-10")));
        RecurringTransaction esgotada = umaAssinatura(Schedule.of(Frequency.MONTHLY, d("2026-01-10")).limitedTo(2));
        RecurringTransaction emCurso = umaAssinatura(Schedule.of(Frequency.MONTHLY, d("2026-01-10")));
        assertThat(MonthlyEstimate.of(List.of(encerrada, esgotada), today).fixedCosts()).isEqualTo(Money.of("0.00"));
        assertThat(MonthlyEstimate.of(List.of(encerrada, esgotada, emCurso), today).fixedCosts()).isEqualTo(Money.of("119.00"));
        assertThat(MonthlyEstimate.of(List.of(encerrada), d("2026-03-10")).fixedCosts()).isEqualTo(Money.of("119.00"));
        assertThat(MonthlyEstimate.of(List.of(encerrada), d("2026-03-11")).fixedCosts()).isEqualTo(Money.of("0.00"));
    }

    @Test
    @DisplayName("S-234: realizar (conta e cartão) e pular ocorrência de recorrência desativada é RECURRENCE_INACTIVE, inclusive a atrasada; nada é registrado")
    void naoDeveRealizarNemPularDesativada() {
        RecurringTransaction r = umaAssinatura(Schedule.of(Frequency.MONTHLY, d("2026-10-10")));
        r.deactivate();
        assertThatThrownBy(() -> r.realize(d("2026-10-10"), TX_ID, null, umaContaCorrente(), umaCategoriaDeDespesa()))
                .isInstanceOf(RecurrenceInactiveException.class).hasMessageContaining("desativada").extracting("code").isEqualTo("RECURRENCE_INACTIVE");
        assertThatThrownBy(() -> r.skip(d("2026-11-10"))).isInstanceOf(RecurrenceInactiveException.class);
        RecurringTransaction card = RecurringTransaction.ofCard(REC_ID, USER_ID, Money.of("49.90"), "Streaming", ALIMENTACAO_ID, ROXINHO_ID,
                Schedule.of(Frequency.MONTHLY, d("2026-10-10")));
        card.deactivate();
        assertThatThrownBy(() -> card.realizeOnCard(d("2026-10-10"), TX_ID, null, null, null, d("2026-10-10"), umaCategoriaDeDespesa()))
                .isInstanceOf(RecurrenceInactiveException.class);
        assertThat(r.realizedDates()).isEmpty();
        assertThat(r.skippedDates()).isEmpty();
    }

    @Test
    @DisplayName("S-236: remover o fim de uma recorrência encerrada por endDate reabriria: RECURRENCE_ENDED; em curso, com fim futuro ou esgotada por quantidade, segue válido")
    void naoDeveReabrirEmSilencio() {
        LocalDate today = d("2026-10-10");
        RecurringTransaction encerrada = umaAssinatura(Schedule.of(Frequency.MONTHLY, d("2026-01-10")).until(d("2026-03-10")));
        assertThatThrownBy(() -> encerrada.ensureEndRemovalDoesNotReopen(today)).isInstanceOf(RecurrenceEndedException.class)
                .extracting("code").isEqualTo("RECURRENCE_ENDED");
        assertThatCode(() -> umaAssinatura(Schedule.of(Frequency.MONTHLY, d("2026-10-10")).until(d("2026-12-10"))).ensureEndRemovalDoesNotReopen(today)).doesNotThrowAnyException();
        assertThatCode(() -> umaAssinatura(Schedule.of(Frequency.MONTHLY, d("2026-01-10"))).ensureEndRemovalDoesNotReopen(today)).doesNotThrowAnyException();
        assertThatCode(() -> umaAssinatura(Schedule.of(Frequency.MONTHLY, d("2026-01-10")).limitedTo(2)).ensureEndRemovalDoesNotReopen(today)).doesNotThrowAnyException();
    }
}
