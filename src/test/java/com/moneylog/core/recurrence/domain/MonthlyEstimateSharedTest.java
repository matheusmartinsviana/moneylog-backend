package com.moneylog.core.recurrence.domain;

import static com.moneylog.core.account.util.factory.AccountTestFactory.NUBANK_ID;
import static com.moneylog.core.card.util.factory.CreditCardTestFactory.ROXINHO_ID;
import static com.moneylog.core.category.util.factory.CategoryTestFactory.ALIMENTACAO_ID;
import static com.moneylog.core.util.Ids.USER_ID;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.moneylog.core.sk.money.Money;
import com.moneylog.core.transaction.domain.PaymentMethod;
import com.moneylog.core.transaction.domain.ShareLine;
import com.moneylog.core.transaction.domain.SplitMode;
import com.moneylog.core.transaction.domain.TransactionType;

@DisplayName("Estimativa mensal de recorrência com cartão rateado: MINE x GROSS")
class MonthlyEstimateSharedTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 15);
    private static final UUID ANA = UUID.fromString("00000000-0000-0000-0000-0000000a0a01");

    private static RecurringTransaction streamingDividido() {
        return RecurringTransaction.ofCard(UUID.fromString("00000000-0000-0000-0000-0000000c0c10"), USER_ID, Money.of("100.00"), "Streaming",
                ALIMENTACAO_ID, ROXINHO_ID, Schedule.of(Frequency.MONTHLY, LocalDate.of(2026, 9, 10)), SplitMode.BPS,
                List.of(new ShareLine(null, 7000, null), new ShareLine(ANA, 3000, null)));
    }

    private static RecurringTransaction internet() {
        return RecurringTransaction.ofAccount(UUID.fromString("00000000-0000-0000-0000-0000000c0c11"), USER_ID, TransactionType.EXPENSE,
                Money.of("119.00"), "Internet", ALIMENTACAO_ID, NUBANK_ID, PaymentMethod.PIX, Schedule.of(Frequency.MONTHLY, LocalDate.of(2026, 9, 10)));
    }

    @Test
    @DisplayName("MINE conta só a minha parte da recorrência de cartão rateada; GROSS conta o valor cheio")
    void deveSepararMinhaParteDoBruto() {
        List<RecurringTransaction> todas = List.of(streamingDividido(), internet());

        MonthlyEstimate mine = MonthlyEstimate.of(todas, TODAY, false);
        MonthlyEstimate gross = MonthlyEstimate.of(todas, TODAY, true);

        assertThat(mine.fixedCosts()).isEqualTo(Money.of("189.00"));
        assertThat(gross.fixedCosts()).isEqualTo(Money.of("219.00"));
        assertThat(mine.gross()).isFalse();
        assertThat(gross.gross()).isTrue();
    }

    @Test
    @DisplayName("sem rateio MINE e GROSS são idênticos")
    void semRateioNaoMudaNada() {
        List<RecurringTransaction> todas = List.of(internet());

        assertThat(MonthlyEstimate.of(todas, TODAY, false).fixedCosts()).isEqualTo(MonthlyEstimate.of(todas, TODAY, true).fixedCosts());
    }

    @Test
    @DisplayName("recorrência rateada encerrada ou inativa não entra em nenhuma visão")
    void encerradaNaoEntra() {
        RecurringTransaction rateada = streamingDividido();
        rateada.deactivate();

        assertThat(MonthlyEstimate.of(List.of(rateada), TODAY, false).fixedCosts()).isEqualTo(Money.of("0.00"));
        assertThat(MonthlyEstimate.of(List.of(rateada), TODAY, true).fixedCosts()).isEqualTo(Money.of("0.00"));
    }
}
