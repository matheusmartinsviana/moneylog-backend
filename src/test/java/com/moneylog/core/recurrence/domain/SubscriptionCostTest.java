package com.moneylog.core.recurrence.domain;

import static com.moneylog.core.util.Ids.USER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.moneylog.core.account.domain.Account;
import com.moneylog.core.account.util.factory.AccountTestFactory;
import com.moneylog.core.category.domain.Category;
import com.moneylog.core.category.util.factory.CategoryTestFactory;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.transaction.domain.ForeignCharge;
import com.moneylog.core.transaction.domain.PaymentMethod;
import com.moneylog.core.transaction.domain.Transaction;
import com.moneylog.core.transaction.domain.TransactionType;
import com.moneylog.core.transaction.exception.FxExceptions.FxInvalidException;
import com.moneylog.core.transaction.exception.FxExceptions.FxRateRequiredException;

@DisplayName("Assinatura: custo mensal equivalente e câmbio por cobrança")
class SubscriptionCostTest {

    private static final LocalDate START = LocalDate.of(2026, 10, 5);
    private static final CurrencyCode USD = CurrencyCode.of("USD");

    private final Account account = AccountTestFactory.umaContaCorrente();
    private final Category category = CategoryTestFactory.umaCategoriaDeDespesa();

    private RecurringTransaction recurrence(String amount, Frequency frequency) {
        return RecurringTransaction.ofAccount(UUID.randomUUID(), USER_ID, TransactionType.EXPENSE, Money.of(amount), "Assinatura",
                category.id(), account.id(), PaymentMethod.PIX, new Schedule(frequency, 1, null, START, null, null));
    }

    @Test
    @DisplayName("semanal de 5,30 equivale a 22,97 por mês e o total não acumula erro de arredondamento")
    void semanalSemErroAcumulado() {
        RecurringTransaction weekly = recurrence("5.30", Frequency.WEEKLY);

        assertThat(SubscriptionCost.full(weekly).money()).isEqualTo(Money.of("22.97"));
        List<SubscriptionCost.Monthly> three = List.of(SubscriptionCost.full(weekly), SubscriptionCost.full(weekly),
                SubscriptionCost.full(weekly));
        assertThat(SubscriptionCost.sum(three).money()).isEqualTo(Money.of("68.90"));
    }

    @Test
    @DisplayName("COMPANY fica fora da minha parte e dentro do total")
    void empresaForaDaMinhaParte() {
        RecurringTransaction company = recurrence("100.00", Frequency.MONTHLY);
        company.defineSubscriptionTraits(new SubscriptionTraits(true, RecurrenceScope.COMPANY, null, null, null));

        assertThat(SubscriptionCost.full(company).money()).isEqualTo(Money.of("100.00"));
        assertThat(SubscriptionCost.mine(company).money()).isEqualTo(Money.of("0.00"));
    }

    @Test
    @DisplayName("diária e anual usam 365/12 e 1/12")
    void diariaEAnual() {
        assertThat(SubscriptionCost.full(recurrence("1.00", Frequency.DAILY)).money()).isEqualTo(Money.of("30.42"));
        assertThat(SubscriptionCost.full(recurrence("120.00", Frequency.YEARLY)).money()).isEqualTo(Money.of("10.00"));
    }

    @Test
    @DisplayName("cobrança em USD usa o câmbio informado a cada confirmação e a recorrência segue com o câmbio de referência")
    void cambioPorCobranca() {
        RecurringTransaction usd = recurrence("25.88", Frequency.MONTHLY);
        usd.defineForeignBasis(ForeignCharge.of(Money.of("5.00", USD), new BigDecimal("5.000000"), 350));

        ForeignCharge charge = usd.chargeFor(null, new BigDecimal("5.2"), null);
        Transaction realized = usd.realize(START, UUID.randomUUID(), null, account, category, PaymentMethod.PIX, charge);

        assertThat(realized.amount()).isEqualTo(Money.of("26.91"));
        assertThat(realized.foreignCharge().fxRate()).isEqualByComparingTo("5.2");
        assertThat(usd.foreignBasis().fxRate()).isEqualByComparingTo("5.0");
        assertThat(usd.amount()).isEqualTo(Money.of("25.88"));
    }

    @Test
    @DisplayName("recorrência em moeda estrangeira sem câmbio na confirmação é FX_RATE_REQUIRED")
    void cambioObrigatorioNaConfirmacao() {
        RecurringTransaction usd = recurrence("25.88", Frequency.MONTHLY);
        usd.defineForeignBasis(ForeignCharge.of(Money.of("5.00", USD), new BigDecimal("5.000000"), 350));

        assertThatThrownBy(() -> usd.chargeFor(null, null, null)).isInstanceOf(FxRateRequiredException.class);
        assertThatThrownBy(() -> usd.realize(START, UUID.randomUUID(), null, account, category, PaymentMethod.PIX, null))
                .isInstanceOf(FxRateRequiredException.class);
    }

    @Test
    @DisplayName("recorrência em BRL com câmbio é FX_INVALID e sem câmbio segue como antes")
    void recorrenciaEmReal() {
        RecurringTransaction brl = recurrence("25.00", Frequency.MONTHLY);

        assertThatThrownBy(() -> brl.chargeFor(null, new BigDecimal("5"), null)).isInstanceOf(FxInvalidException.class);
        Transaction realized = brl.realize(START, UUID.randomUUID(), null, account, category, PaymentMethod.PIX);
        assertThat(realized.amount()).isEqualTo(Money.of("25.00"));
        assertThat(realized.foreignCharge()).isNull();
    }

    @Test
    @DisplayName("encerrar com data mantém as ocorrências até ela e nenhuma depois")
    void ativaAte() {
        RecurringTransaction monthly = recurrence("20.00", Frequency.MONTHLY);

        monthly.endOn(LocalDate.of(2026, 12, 5));

        assertThat(monthly.occurrences(START, LocalDate.of(2027, 3, 31))).extracting(Occurrence::date)
                .containsExactly(LocalDate.of(2026, 10, 5), LocalDate.of(2026, 11, 5), LocalDate.of(2026, 12, 5));
    }
}
