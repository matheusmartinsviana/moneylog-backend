package com.moneylog.core.recurrence.app.usecase;

import static com.moneylog.core.card.util.factory.CreditCardTestFactory.ROXINHO_ID;
import static com.moneylog.core.category.util.factory.CategoryTestFactory.ALIMENTACAO_ID;
import static com.moneylog.core.util.Ids.OTHER_USER_ID;
import static com.moneylog.core.util.Ids.USER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.moneylog.core.recurrence.app.gateways.RecurrenceGateway;
import com.moneylog.core.recurrence.app.usecase.GetRecurrenceSummaryUseCase.GetRecurrenceSummaryQuery;
import com.moneylog.core.recurrence.domain.Frequency;
import com.moneylog.core.recurrence.domain.MonthlyEstimate;
import com.moneylog.core.recurrence.domain.RecurringTransaction;
import com.moneylog.core.recurrence.domain.Schedule;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.core.transaction.domain.ShareLine;
import com.moneylog.core.transaction.domain.SplitMode;

@DisplayName("Resumo de recorrência: visão MINE e GROSS com cartão rateado")
class GetRecurrenceSummaryViewTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 15);
    private static final UUID ANA = UUID.fromString("00000000-0000-0000-0000-0000000a0a01");

    private final RecurrenceGateway recurrences = mock(RecurrenceGateway.class);
    private final TimeProvider time = mock(TimeProvider.class);
    private final GetRecurrenceSummaryUseCaseImpl summary = new GetRecurrenceSummaryUseCaseImpl(recurrences, time);

    private RecurringTransaction streamingDividido() {
        return RecurringTransaction.ofCard(UUID.fromString("00000000-0000-0000-0000-0000000c0c10"), USER_ID, Money.of("100.00"), "Streaming",
                ALIMENTACAO_ID, ROXINHO_ID, Schedule.of(Frequency.MONTHLY, LocalDate.of(2026, 9, 10)), SplitMode.EQUAL,
                List.of(new ShareLine(null, null, null), new ShareLine(ANA, null, null)));
    }

    @Test
    @DisplayName("MINE devolve a minha parte (50,00) e GROSS o valor cheio (100,00); lê só as recorrências do usuário do token")
    void deveResumirNasDuasVisoes() {
        when(time.today()).thenReturn(TODAY);
        when(recurrences.findAll(USER_ID)).thenReturn(List.of(streamingDividido()));

        MonthlyEstimate mine = summary.handle(new GetRecurrenceSummaryQuery(USER_ID, false));
        MonthlyEstimate gross = summary.handle(new GetRecurrenceSummaryQuery(USER_ID, true));

        assertThat(mine.fixedCosts()).isEqualTo(Money.of("50.00"));
        assertThat(gross.fixedCosts()).isEqualTo(Money.of("100.00"));
        assertThat(mine.gross()).isFalse();
        assertThat(gross.gross()).isTrue();
        verify(recurrences, org.mockito.Mockito.times(2)).findAll(USER_ID);
        verifyNoMoreInteractions(recurrences);
    }

    @Test
    @DisplayName("outro usuário não enxerga a recorrência rateada")
    void naoVazaEntreUsuarios() {
        when(time.today()).thenReturn(TODAY);
        when(recurrences.findAll(OTHER_USER_ID)).thenReturn(List.of());

        assertThat(summary.handle(new GetRecurrenceSummaryQuery(OTHER_USER_ID, true)).fixedCosts()).isEqualTo(Money.of("0.00"));
        verify(recurrences).findAll(OTHER_USER_ID);
    }
}
