package com.moneylog.core.finance.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.moneylog.core.card.domain.TransactionInstallment;
import com.moneylog.core.finance.util.FinanceScenarioFactory;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.time.Competence;
import com.moneylog.core.thirdparty.domain.ThirdPartyReceipt;
import com.moneylog.core.transaction.domain.CardTransactions;
import com.moneylog.core.transaction.domain.ShareLine;
import com.moneylog.core.transaction.domain.SplitMode;
import com.moneylog.core.transaction.domain.Transaction;

@DisplayName("Mês previsto do recebimento de compra rateada (ADR-023): padrão = vencimento da fatura, editável por parcela")
class CardShareReceivablesExpectedMonthTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 15);
    private static final UUID ANA = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    private final FinanceScenarioFactory scenario = FinanceScenarioFactory.umCenarioBase();

    private Transaction purchase() {
        return CardTransactions.purchase(scenario.header(TODAY, "Jantar"), Money.of("300.00"), scenario.roxinho, scenario.alimentacao, 3,
                scenario.chain(), TODAY, SplitMode.BPS, List.of(new ShareLine(null, 5000, null), new ShareLine(ANA, 5000, null)));
    }

    private FinancialRecords records(Transaction tx) {
        return new FinancialRecords(List.of(), List.of(scenario.roxinho), List.of(tx), List.of());
    }

    private Competence dueMonth(TransactionInstallment installment) {
        return Competence.from(scenario.roxinho.calendar()
                .periodClosingIn(java.time.YearMonth.from(installment.invoiceClosingDate())).dueDate());
    }

    private CardShareReceivables.PurchaseLine line(Transaction tx, Map<CardShareReceivables.ShareKey, Competence> overrides) {
        List<CardShareReceivables.PurchaseLine> lines = CardShareReceivables.lines(records(tx), List.<ThirdPartyReceipt>of(), overrides);
        assertThat(lines).hasSize(1);
        return lines.getFirst();
    }

    @Test
    @DisplayName("sem edição, cada parcela é prevista no mês do vencimento da fatura dela, não no mês do consumo")
    void padraoEOVencimentoDaFatura() {
        Transaction tx = purchase();

        CardShareReceivables.PurchaseLine line = line(tx, Map.of());

        assertThat(line.parts()).hasSize(3);
        for (CardShareReceivables.Part part : line.parts()) {
            TransactionInstallment installment = tx.installments().stream().filter(i -> i.id().equals(part.installmentId())).findFirst().orElseThrow();
            assertThat(part.expectedMonth()).isEqualTo(dueMonth(installment));
            assertThat(part.dueMonth()).isEqualTo(dueMonth(installment));
            assertThat(part.overridden()).isFalse();
            assertThat(part.owed()).isEqualTo(Money.of("50.00"));
        }
        assertThat(line.expectedMonths().values().stream().map(CardShareReceivables.MonthAmounts::pending).reduce(Money.zero(CurrencyCode.BRL), Money::add))
                .isEqualTo(Money.of("150.00"));
    }

    @Test
    @DisplayName("editar a parcela 2 move só o valor dela para o mês escolhido; o total a receber não muda e o mês antigo deixa de contá-la")
    void edicaoMoveSoAqueleRecebivel() {
        Transaction tx = purchase();
        CardShareReceivables.PurchaseLine base = line(tx, Map.of());
        CardShareReceivables.Part second = base.parts().get(1);
        Competence target = Competence.parse("2027-06");

        CardShareReceivables.PurchaseLine edited = line(tx, Map.of(new CardShareReceivables.ShareKey(second.installmentId(), ANA), target));

        assertThat(edited.expectedMonths().get(target).pending()).isEqualTo(Money.of("50.00"));
        assertThat(edited.expectedMonths().get(second.expectedMonth())).isNull();
        assertThat(edited.parts().stream().filter(CardShareReceivables.Part::overridden)).hasSize(1);
        assertThat(edited.pending()).isEqualTo(base.pending());
        assertThat(edited.months()).isEqualTo(base.months());
    }

    @Test
    @DisplayName("mês sem recebível previsto não aparece; editar para o mesmo mês de outra parcela soma as duas")
    void mesSemRecebivelENoMesmoMes() {
        Transaction tx = purchase();
        CardShareReceivables.PurchaseLine base = line(tx, Map.of());
        CardShareReceivables.Part first = base.parts().get(0);
        CardShareReceivables.Part second = base.parts().get(1);

        CardShareReceivables.PurchaseLine edited = line(tx, Map.of(new CardShareReceivables.ShareKey(first.installmentId(), ANA), second.expectedMonth()));

        assertThat(edited.expectedMonths().get(Competence.parse("2030-01"))).isNull();
        assertThat(edited.expectedMonths().get(second.expectedMonth()).pending()).isEqualTo(Money.of("100.00"));
        assertThat(edited.expectedMonths().get(first.dueMonth())).isNull();
    }

    @Test
    @DisplayName("o consumo (months) continua na competência do consumo, sem mudar com o mês previsto")
    void consumoNaoSeMove() {
        Transaction tx = purchase();
        CardShareReceivables.Part first = line(tx, Map.of()).parts().get(0);

        CardShareReceivables.PurchaseLine edited = line(tx, Map.of(new CardShareReceivables.ShareKey(first.installmentId(), ANA), Competence.parse("2028-01")));

        assertThat(edited.months().keySet()).isEqualTo(line(tx, Map.of()).months().keySet());
    }
}
