package com.moneylog.core.recurrence.domain;

import static com.moneylog.core.account.util.factory.AccountTestFactory.NUBANK_ID;
import static com.moneylog.core.account.util.factory.AccountTestFactory.XP_ID;
import static com.moneylog.core.card.util.factory.CreditCardTestFactory.ROXINHO_ID;
import static com.moneylog.core.category.util.factory.CategoryTestFactory.ALIMENTACAO_ID;
import static com.moneylog.core.util.Ids.USER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.moneylog.core.recurrence.exception.RecurrenceExceptions.InvalidRecurrenceException;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.transaction.domain.PaymentMethod;
import com.moneylog.core.transaction.domain.TransactionType;

@DisplayName("Recorrência: edição dos dados do modelo e encerramento (S-139)")
class RecurringTransactionEdicaoTest {

    private static final UUID REC_ID = UUID.fromString("00000000-0000-0000-0000-0000000c0c11");
    private static final UUID NEW_CATEGORY = UUID.fromString("00000000-0000-0000-0000-00000000c0c9");

    private static LocalDate d(String iso) {
        return LocalDate.parse(iso);
    }

    private static RecurringTransaction internet() {
        return RecurringTransaction.ofAccount(REC_ID, USER_ID, TransactionType.EXPENSE, Money.of("119.00"), "Internet", ALIMENTACAO_ID, NUBANK_ID,
                PaymentMethod.PIX, Schedule.of(Frequency.MONTHLY, d("2026-10-10")));
    }

    @Test
    @DisplayName("updateDetails muda valor, descrição, categoria e conta/meio; as ocorrências futuras usam o valor novo; tipo, frequência e início não mudam")
    void deveEditarOsDados() {
        RecurringTransaction r = internet();
        r.updateDetails(Money.of("129.90"), "Internet fibra", NEW_CATEGORY, XP_ID, PaymentMethod.BOLETO, null);
        assertThat(r.amount()).isEqualTo(Money.of("129.90"));
        assertThat(r.description()).isEqualTo("Internet fibra");
        assertThat(r.categoryId()).isEqualTo(NEW_CATEGORY);
        assertThat(r.accountId()).isEqualTo(XP_ID);
        assertThat(r.paymentMethod()).isEqualTo(PaymentMethod.BOLETO);
        assertThat(r.type()).isEqualTo(TransactionType.EXPENSE);
        assertThat(r.schedule().frequency()).isEqualTo(Frequency.MONTHLY);
        assertThat(r.schedule().startDate()).isEqualTo(d("2026-10-10"));
        assertThat(r.occurrences(d("2026-10-01"), d("2026-11-30"))).extracting(Occurrence::amount).containsOnly(Money.of("129.90"));
    }

    @Test
    @DisplayName("updateDetails segue as regras da criação: valor ≤ 0, conta e cartão juntos, cartão com método e sem categoria são RECURRENCE_INVALID")
    void deveRecusarModeloInvalido() {
        RecurringTransaction r = internet();
        assertThatThrownBy(() -> r.updateDetails(Money.of("0.00"), "x", ALIMENTACAO_ID, NUBANK_ID, PaymentMethod.PIX, null))
                .isInstanceOf(InvalidRecurrenceException.class);
        assertThatThrownBy(() -> r.updateDetails(Money.of("10.00"), "x", ALIMENTACAO_ID, NUBANK_ID, PaymentMethod.PIX, ROXINHO_ID))
                .isInstanceOf(InvalidRecurrenceException.class);
        assertThatThrownBy(() -> r.updateDetails(Money.of("10.00"), "x", ALIMENTACAO_ID, null, PaymentMethod.PIX, null))
                .isInstanceOf(InvalidRecurrenceException.class);
        assertThatThrownBy(() -> r.updateDetails(Money.of("10.00"), "x", null, NUBANK_ID, PaymentMethod.PIX, null))
                .isInstanceOf(InvalidRecurrenceException.class);
        assertThatThrownBy(() -> r.updateDetails(Money.of("10.00"), "x".repeat(201), ALIMENTACAO_ID, NUBANK_ID, PaymentMethod.PIX, null))
                .isInstanceOf(InvalidRecurrenceException.class);
        assertThat(r.amount()).isEqualTo(Money.of("119.00"));
        RecurringTransaction card = RecurringTransaction.ofCard(UUID.randomUUID(), USER_ID, Money.of("49.90"), "Streaming", ALIMENTACAO_ID, ROXINHO_ID,
                Schedule.of(Frequency.MONTHLY, d("2026-09-15")));
        card.updateDetails(Money.of("59.90"), "Streaming 4K", ALIMENTACAO_ID, null, null, ROXINHO_ID);
        assertThat(card.amount()).isEqualTo(Money.of("59.90"));
        assertThatThrownBy(() -> card.updateDetails(Money.of("59.90"), "x", ALIMENTACAO_ID, NUBANK_ID, null, ROXINHO_ID))
                .isInstanceOf(InvalidRecurrenceException.class);
    }

    @Test
    @DisplayName("endOn corta as ocorrências depois da data; changeEnd(null) remove o fim; fim anterior ao início, a uma realizada ou a uma pulada é recusado")
    void deveEncerrarComGuardas() {
        RecurringTransaction r = internet();
        r.endOn(d("2026-12-20"));
        assertThat(r.schedule().endDate()).isEqualTo(d("2026-12-20"));
        assertThat(r.occurrences(d("2026-10-01"), d("2027-03-31"))).extracting(Occurrence::date)
                .containsExactly(d("2026-10-10"), d("2026-11-10"), d("2026-12-10"));
        r.changeEnd(null);
        assertThat(r.schedule().endDate()).isNull();
        assertThat(r.schedule().maxOccurrences()).isNull();

        assertThatThrownBy(() -> r.endOn(null)).isInstanceOf(InvalidRecurrenceException.class);
        assertThatThrownBy(() -> r.endOn(d("2026-09-01"))).isInstanceOf(InvalidRecurrenceException.class);

        RecurringTransaction restored = RecurringTransaction.restore(REC_ID, USER_ID, TransactionType.EXPENSE, Money.of("119.00"), "Internet",
                ALIMENTACAO_ID, NUBANK_ID, null, PaymentMethod.PIX, Schedule.of(Frequency.MONTHLY, d("2026-10-10")), List.of(d("2026-11-10")),
                List.of(d("2026-12-10")), true);
        assertThatThrownBy(() -> restored.endOn(d("2026-11-09"))).isInstanceOf(InvalidRecurrenceException.class)
                .hasMessageContaining("realizada");
        assertThatThrownBy(() -> restored.endOn(d("2026-12-09"))).isInstanceOf(InvalidRecurrenceException.class)
                .hasMessageContaining("pulada");
        restored.endOn(d("2026-12-10"));
        assertThat(restored.schedule().endDate()).isEqualTo(d("2026-12-10"));
        assertThat(restored.realizedDates()).containsExactly(d("2026-11-10"));
        assertThat(restored.skippedDates()).containsExactly(d("2026-12-10"));
        RecurringTransaction limited = RecurringTransaction.ofAccount(UUID.randomUUID(), USER_ID, TransactionType.EXPENSE, Money.of("1.00"), "x",
                ALIMENTACAO_ID, NUBANK_ID, PaymentMethod.PIX, Schedule.of(Frequency.MONTHLY, d("2026-10-10")).limitedTo(3));
        limited.endOn(d("2027-12-31"));
        assertThat(limited.schedule().maxOccurrences()).isEqualTo(3);
    }
}
