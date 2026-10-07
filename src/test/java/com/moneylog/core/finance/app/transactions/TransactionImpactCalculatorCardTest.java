package com.moneylog.core.finance.app.transactions;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.moneylog.core.account.domain.Account;
import com.moneylog.core.finance.app.transactions.TransactionDetails.Impact;
import com.moneylog.core.finance.util.FinanceScenarioFactory;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.transaction.domain.Transaction;

/** O impacto da compra parcelada: patrimônio só até hoje (S-8) e o compromisso que a compra cria (D1 §2.8). */
@DisplayName("TransactionImpactCalculator com cartão (T53)")
class TransactionImpactCalculatorCardTest {

    private final FinanceScenarioFactory scenario = FinanceScenarioFactory.umCenarioBase();
    private final Map<UUID, Account> accounts = Map.of();

    @Test
    @DisplayName("em 10/10 o patrimônio da compra 6x é −200 (só a parcela 1); em 10/12 já são −600; sem 'hoje' soma tudo (−1.200)")
    void deveContarNoPatrimonioSoAteHoje() {
        Transaction purchase = scenario.caso2CompraParcelada();

        Impact atPurchase = TransactionImpactCalculator.of(purchase, accounts, LocalDate.of(2026, 10, 10), Money.of("1000.00"));
        Impact later = TransactionImpactCalculator.of(purchase, accounts, LocalDate.of(2026, 12, 10), Money.of("600.00"));
        Impact noClock = TransactionImpactCalculator.of(purchase, accounts);

        assertThat(atPurchase.netWorth()).isEqualTo(Money.of("-200.00"));
        assertThat(atPurchase.commitment()).isEqualTo(Money.of("1000.00"));
        assertThat(later.netWorth()).isEqualTo(Money.of("-600.00"));
        assertThat(noClock.netWorth()).isEqualTo(Money.of("-1200.00"));
        assertThat(noClock.commitment()).isEqualTo(Money.of("0.00"));
        assertThat(atPurchase.cash()).isEqualTo(Money.of("0.00"));
        assertThat(atPurchase.consumption()).hasSize(6);
    }

    @Test
    @DisplayName("a data não filtra o patrimônio dos lançamentos de conta (comportamento da Etapa 2 intacto)")
    void naoDeveFiltrarContas() {
        Transaction expense = scenario.caso1DespesaPix();

        Impact impact = TransactionImpactCalculator.of(expense, Map.of(scenario.nubank.id(), scenario.nubank), LocalDate.of(2026, 9, 1),
                Money.of("0.00"));

        assertThat(impact.cash()).isEqualTo(Money.of("-100.00"));
        assertThat(impact.netWorth()).isEqualTo(Money.of("-100.00"));
    }
}
