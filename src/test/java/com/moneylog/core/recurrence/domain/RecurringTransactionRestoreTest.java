package com.moneylog.core.recurrence.domain;

import static com.moneylog.core.account.util.factory.AccountTestFactory.NUBANK_ID;
import static com.moneylog.core.account.util.factory.AccountTestFactory.umaContaCorrente;
import static com.moneylog.core.card.util.factory.CreditCardTestFactory.ROXINHO_ID;
import static com.moneylog.core.category.util.factory.CategoryTestFactory.ALIMENTACAO_ID;
import static com.moneylog.core.category.util.factory.CategoryTestFactory.SALARIO_ID;
import static com.moneylog.core.category.util.factory.CategoryTestFactory.umaCategoriaDeDespesa;
import static com.moneylog.core.util.Ids.USER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.moneylog.core.recurrence.exception.RecurrenceExceptions.OccurrenceAlreadyRealizedException;
import com.moneylog.core.recurrence.exception.RecurrenceExceptions.OccurrenceSkippedException;
import com.moneylog.core.recurrence.exception.RecurrenceExceptions.RecurrenceStateInconsistentException;
import com.moneylog.core.sk.exception.DomainException;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.transaction.domain.PaymentMethod;
import com.moneylog.core.transaction.domain.TransactionType;

/** T45: reidratação de {@link RecurringTransaction} para o mapper da infra. */
@DisplayName("RecurringTransaction.restore (T45)")
class RecurringTransactionRestoreTest {

    private static final UUID REC_ID = UUID.fromString("00000000-0000-0000-0000-0000000c0d01");
    private static final UUID TX_ID = UUID.fromString("00000000-0000-0000-0000-0000000c0d02");

    private static LocalDate d(String iso) {
        return LocalDate.parse(iso);
    }

    private static RecurringTransaction assinatura() {
        return RecurringTransaction.ofAccount(REC_ID, USER_ID, TransactionType.EXPENSE, Money.of("119.00"), "Streaming",
                ALIMENTACAO_ID, NUBANK_ID, PaymentMethod.DEBIT, Schedule.of(Frequency.MONTHLY, d("2026-10-05")));
    }

    private static RecurringTransaction restore(RecurringTransaction r) {
        return RecurringTransaction.restore(r.id(), r.userId(), r.type(), r.amount(), r.description(), r.categoryId(),
                r.accountId(), r.cardId(), r.paymentMethod(), r.schedule(), r.realizedDates(), r.skippedDates(), r.isActive());
    }

    private static List<LocalDate> previstas(RecurringTransaction r) {
        return r.occurrences(d("2026-10-01"), d("2027-03-31")).stream().map(Occurrence::date).toList();
    }

    @Test
    @DisplayName("CA2: com ocorrências realizadas e puladas, o ida-e-volta preserva as próximas ocorrências e os campos")
    void devePreservarRealizadasEPuladas() {
        RecurringTransaction original = assinatura();
        original.realize(d("2026-10-05"), TX_ID, null, umaContaCorrente(), umaCategoriaDeDespesa());
        original.skip(d("2026-11-05"));
        original.markRealized(d("2026-12-05"));

        RecurringTransaction restored = restore(original);

        assertThat(previstas(restored)).containsExactly(d("2027-01-05"), d("2027-02-05"), d("2027-03-05"));
        assertThat(previstas(restored)).isEqualTo(previstas(original));
        assertThat(restored.realizedDates()).containsExactly(d("2026-10-05"), d("2026-12-05"));
        assertThat(restored.skippedDates()).containsExactly(d("2026-11-05"));
        assertThat(restored.id()).isEqualTo(REC_ID);
        assertThat(restored.userId()).isEqualTo(USER_ID);
        assertThat(restored.type()).isEqualTo(TransactionType.EXPENSE);
        assertThat(restored.amount()).isEqualTo(Money.of("119.00"));
        assertThat(restored.description()).isEqualTo("Streaming");
        assertThat(restored.categoryId()).isEqualTo(ALIMENTACAO_ID);
        assertThat(restored.accountId()).isEqualTo(NUBANK_ID);
        assertThat(restored.paymentMethod()).isEqualTo(PaymentMethod.DEBIT);
        assertThat(restored.schedule()).isEqualTo(original.schedule());
        assertThat(restored.isActive()).isTrue();
    }

    @Test
    @DisplayName("a reidratada continua recusando realizar de novo uma ocorrência realizada ou pulada")
    void deveContinuarRecusandoReprocessar() {
        RecurringTransaction original = assinatura();
        original.realize(d("2026-10-05"), TX_ID, null, umaContaCorrente(), umaCategoriaDeDespesa());
        original.skip(d("2026-11-05"));

        RecurringTransaction restored = restore(original);

        assertThatThrownBy(() -> restored.realize(d("2026-10-05"), TX_ID, null, umaContaCorrente(), umaCategoriaDeDespesa()))
                .isInstanceOf(OccurrenceAlreadyRealizedException.class);
        assertThatThrownBy(() -> restored.skip(d("2026-11-05"))).isInstanceOf(OccurrenceSkippedException.class);
    }

    @Test
    @DisplayName("recorrência desativada volta desativada e sem ocorrências previstas")
    void deveManterDesativada() {
        RecurringTransaction original = assinatura();
        original.deactivate();

        RecurringTransaction restored = restore(original);

        assertThat(restored.isActive()).isFalse();
        assertThat(previstas(restored)).isEmpty();
    }

    @Test
    @DisplayName("receita e compra no cartão à vista também reidratam, com agenda personalizada e limitada")
    void deveReidratarReceitaECartao() {
        RecurringTransaction receita = RecurringTransaction.ofAccount(REC_ID, USER_ID, TransactionType.INCOME, Money.of("5000.00"),
                null, SALARIO_ID, NUBANK_ID, PaymentMethod.TRANSFER, Schedule.custom(2, IntervalUnit.WEEKS, d("2026-10-02")).limitedTo(4));
        RecurringTransaction cartao = RecurringTransaction.ofCard(TX_ID, USER_ID, Money.of("40.00"), "Cinema", ALIMENTACAO_ID,
                ROXINHO_ID, Schedule.of(Frequency.YEARLY, d("2026-10-31")).until(d("2028-12-31")));

        RecurringTransaction receitaRestaurada = restore(receita);
        RecurringTransaction cartaoRestaurado = restore(cartao);

        assertThat(receitaRestaurada.direction()).isEqualTo(receita.direction());
        assertThat(receitaRestaurada.schedule()).isEqualTo(receita.schedule());
        assertThat(previstas(receitaRestaurada)).isEqualTo(previstas(receita));
        assertThat(cartaoRestaurado.cardId()).isEqualTo(ROXINHO_ID);
        assertThat(cartaoRestaurado.accountId()).isNull();
        assertThat(cartaoRestaurado.type()).isEqualTo(TransactionType.CARD_PURCHASE);
    }

    @Test
    @DisplayName("CA3: data que não é ocorrência, data realizada e pulada ao mesmo tempo ou repetida lançam a exceção da feature")
    void deveRecusarEstadoCorrompido() {
        RecurringTransaction r = assinatura();

        assertThatThrownBy(() -> RecurringTransaction.restore(r.id(), r.userId(), r.type(), r.amount(), r.description(),
                r.categoryId(), r.accountId(), r.cardId(), r.paymentMethod(), r.schedule(), List.of(d("2026-10-06")), List.of(), true))
                .isInstanceOf(RecurrenceStateInconsistentException.class).isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("code", "RECURRENCE_STATE_INCONSISTENT").hasMessageContaining("não é ocorrência");
        assertThatThrownBy(() -> RecurringTransaction.restore(r.id(), r.userId(), r.type(), r.amount(), r.description(),
                r.categoryId(), r.accountId(), r.cardId(), r.paymentMethod(), r.schedule(), List.of(), List.of(d("2026-10-06")), true))
                .isInstanceOf(RecurrenceStateInconsistentException.class);
        assertThatThrownBy(() -> RecurringTransaction.restore(r.id(), r.userId(), r.type(), r.amount(), r.description(),
                r.categoryId(), r.accountId(), r.cardId(), r.paymentMethod(), r.schedule(), List.of(d("2026-10-05")),
                List.of(d("2026-10-05")), true)).isInstanceOf(RecurrenceStateInconsistentException.class)
                .hasMessageContaining("mais de uma vez");
        assertThatThrownBy(() -> RecurringTransaction.restore(r.id(), r.userId(), r.type(), r.amount(), r.description(),
                r.categoryId(), r.accountId(), r.cardId(), r.paymentMethod(), r.schedule(),
                List.of(d("2026-10-05"), d("2026-10-05")), List.of(), true)).isInstanceOf(RecurrenceStateInconsistentException.class);
    }

    @Test
    @DisplayName("CA3: campos que não batem com o tipo (valor zero, cartão com conta, despesa sem conta) lançam a exceção da feature")
    void deveEmbrulharValidacoesDeConstrucao() {
        RecurringTransaction r = assinatura();

        assertThatThrownBy(() -> RecurringTransaction.restore(r.id(), r.userId(), r.type(), Money.of("0.00"), r.description(),
                r.categoryId(), r.accountId(), null, r.paymentMethod(), r.schedule(), List.of(), List.of(), true))
                .isInstanceOf(RecurrenceStateInconsistentException.class).hasCauseInstanceOf(DomainException.class);
        assertThatThrownBy(() -> RecurringTransaction.restore(r.id(), r.userId(), TransactionType.CARD_PURCHASE, r.amount(),
                r.description(), r.categoryId(), r.accountId(), ROXINHO_ID, null, r.schedule(), List.of(), List.of(), true))
                .isInstanceOf(RecurrenceStateInconsistentException.class);
        assertThatThrownBy(() -> RecurringTransaction.restore(r.id(), r.userId(), r.type(), r.amount(), r.description(),
                r.categoryId(), r.accountId(), null, null, r.schedule(), List.of(), List.of(), true))
                .isInstanceOf(RecurrenceStateInconsistentException.class);
        // conta a definir (sem conta nem método) é um estado válido (2.14.0)
        assertThat(RecurringTransaction.restore(r.id(), r.userId(), r.type(), r.amount(), r.description(), r.categoryId(), null, null, null,
                r.schedule(), List.of(), List.of(), true).accountId()).isNull();
        assertThatThrownBy(() -> RecurringTransaction.restore(r.id(), r.userId(), TransactionType.TRANSFER, r.amount(),
                r.description(), r.categoryId(), r.accountId(), null, r.paymentMethod(), r.schedule(), List.of(), List.of(), true))
                .isInstanceOf(RecurrenceStateInconsistentException.class);
    }
}
