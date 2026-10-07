package com.moneylog.infra.dashboard;

import static com.moneylog.core.finance.util.FinanceScenarioFactory.umCenarioBase;
import static com.moneylog.core.util.Ids.USER_ID;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.moneylog.core.debt.domain.Debt;
import com.moneylog.core.debt.domain.DebtDirection;
import com.moneylog.core.debt.util.factory.PersonTestFactory;
import com.moneylog.core.finance.app.dashboard.UpcomingView;
import com.moneylog.core.finance.domain.CommitmentSchedule;
import com.moneylog.core.finance.util.FinanceScenarioFactory;
import com.moneylog.core.sk.money.Money;
import com.moneylog.infra.dashboard.controller.UpcomingResponse;

@DisplayName("UpcomingResponse: mapeamento de compromissos (T29, S-64)")
class UpcomingResponseTest {

    @Test
    @DisplayName("fatura com parcelas aninhadas, dívida datada e dívida sem data (bucket nulo)")
    void deveMapearItens() {
        FinanceScenarioFactory s = umCenarioBase();
        s.caso2CompraParcelada();
        s.caso8DividaSemMovimentacao();
        s.debts.add(Debt.withoutMovement(java.util.UUID.fromString("00000000-0000-0000-0000-00000000db09"), USER_ID, PersonTestFactory.umPedro(),
                DebtDirection.PAYABLE, Money.of("50.00"), FinanceScenarioFactory.BASE_DATE, null, "sem previsão"));
        CommitmentSchedule schedule = CommitmentSchedule.of(s.records(), List.of(), LocalDate.of(2026, 10, 10));

        UpcomingResponse response = UpcomingResponse.of(new UpcomingView(schedule.items(), schedule.undated(), schedule.totalOut(), schedule.totalIn()));

        assertThat(response.undated()).singleElement().satisfies(item -> {
            assertThat(item.bucket()).isNull();
            assertThat(item.dueDate()).isNull();
            assertThat(item.refType()).isEqualTo("DEBT");
        });
        UpcomingResponse.Item invoice = response.items().stream().filter(i -> i.refType().equals("INVOICE")).findFirst().orElseThrow();
        assertThat(invoice.items()).isNotEmpty().allSatisfy(child -> {
            assertThat(child.kind()).isEqualTo("INSTALLMENT");
            assertThat(child.informational()).isTrue();
            assertThat(child.installment().total()).isEqualTo(6);
        });
        assertThat(invoice.items().get(0).installment().number()).isEqualTo(1);
        assertThat(response.items().stream().filter(i -> i.refType().equals("DEBT")).findFirst().orElseThrow().bucket()).isNotNull();
        assertThat(response.totals().out()).isEqualTo(schedule.totalOut());
    }

    @Test
    @DisplayName("S-258: movimentação futura sai como SCHEDULED_TRANSACTION com refType TRANSACTION, refId da movimentação, rótulo da descrição e sentido do caixa")
    void deveMapearAMovimentacaoFutura() {
        FinanceScenarioFactory s = umCenarioBase();
        com.moneylog.core.transaction.domain.Transaction tx = com.moneylog.core.transaction.domain.AccountTransactions.expense(s.header(LocalDate.of(2026, 10, 15), "Aluguel"),
                Money.of("1000.00"), s.nubank, s.alimentacao, com.moneylog.core.transaction.domain.PaymentMethod.PIX);
        s.transactions.add(tx);
        CommitmentSchedule schedule = CommitmentSchedule.of(s.records(), List.of(), LocalDate.of(2026, 10, 10));

        UpcomingResponse response = UpcomingResponse.of(new UpcomingView(schedule.items(), schedule.undated(), schedule.totalOut(), schedule.totalIn()));

        assertThat(response.items()).singleElement().satisfies(item -> {
            assertThat(item.kind().name()).isEqualTo("SCHEDULED_TRANSACTION");
            assertThat(item.refType()).isEqualTo("TRANSACTION");
            assertThat(item.refId()).isEqualTo(tx.id());
            assertThat(item.label()).isEqualTo("Aluguel");
            assertThat(item.direction().name()).isEqualTo("OUT");
            assertThat(item.amount()).isEqualTo(Money.of("1000.00"));
            assertThat(item.items()).isEmpty();
        });
    }
}
