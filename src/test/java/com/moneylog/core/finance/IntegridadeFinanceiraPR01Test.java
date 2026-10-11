package com.moneylog.core.finance;

import static com.moneylog.core.finance.util.FinanceScenarioFactory.BASE_DATE;
import static com.moneylog.core.finance.util.FinanceScenarioFactory.umCenarioBase;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.moneylog.core.debt.domain.DebtStatus;
import com.moneylog.core.finance.domain.CashPosition;
import com.moneylog.core.finance.domain.ConsumptionReport;
import com.moneylog.core.finance.domain.FinancialRecords;
import com.moneylog.core.finance.domain.Ledger;
import com.moneylog.core.finance.domain.NetWorth;
import com.moneylog.core.finance.util.FinanceScenarioFactory;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.time.Competence;
import com.moneylog.core.transaction.domain.Transaction;

@DisplayName("Integridade financeira: os 9 casos do PR 01 (T8a)")
class IntegridadeFinanceiraPR01Test {

    private static final Competence OUT_26 = Competence.of(2026, 10);

    record Lentes(Money nubank, Money xp, Money xpInvest, Money inAccounts, Money investments,
                  Map<Competence, Money> consumo, Map<Competence, Money> receita, Money patrimonio, Money parcelasFuturas) {

        static Lentes de(FinanceScenarioFactory s, LocalDate asOf) {
            FinancialRecords records = s.records();
            Ledger ledger = records.ledger();
            CashPosition cash = CashPosition.at(records, ledger, asOf);
            Map<Competence, Money> consumo = new LinkedHashMap<>();
            Map<Competence, Money> receita = new LinkedHashMap<>();
            for (int i = 0; i < 7; i++) {
                ConsumptionReport report = ConsumptionReport.of(records, ledger, OUT_26.plusMonths(i));
                consumo.put(OUT_26.plusMonths(i), report.consumption());
                receita.put(OUT_26.plusMonths(i), report.income());
            }
            NetWorth nw = NetWorth.at(ledger, asOf);
            return new Lentes(cash.balanceOf(s.nubank.id()), cash.balanceOf(s.xp.id()), cash.balanceOf(s.xpInvest.id()),
                    cash.inAccounts(), cash.investments(), consumo, receita, nw.total(), nw.cardFutureInstallments());
        }

        Money consumoTotal() {
            return consumo.values().stream().reduce(Money.of("0.00"), Money::add);
        }
    }

    private static Money alimentacao(FinanceScenarioFactory s, Competence c) {
        return s.records().ledger().sum(e -> e.posting().book().equals(s.alimentacao.book()) && e.posting().competence().equals(c));
    }

    private static Money m(String value) {
        return Money.of(value);
    }

    private static void assertSemConsumoNemReceita(Lentes antes, Lentes depois) {
        assertThat(depois.consumo()).isEqualTo(antes.consumo());
        assertThat(depois.receita()).isEqualTo(antes.receita());
    }

    @Test
    @DisplayName("cenário base: Nubank 20.000, XP 0, XP Invest 0, patrimônio 20.000, nada de consumo")
    void deveMontarOCenarioBase() {
        Lentes base = Lentes.de(umCenarioBase(), BASE_DATE);
        assertThat(base.nubank()).isEqualTo(m("20000.00"));
        assertThat(base.xp()).isEqualTo(m("0.00"));
        assertThat(base.xpInvest()).isEqualTo(m("0.00"));
        assertThat(base.patrimonio()).isEqualTo(m("20000.00"));
        assertThat(base.consumoTotal()).isEqualTo(m("0.00"));
    }

    @Test
    @DisplayName("caso 1: R$ 100 Pix restaurante → Nubank −100; Alimentação out/26 +100; patrimônio −100")
    void caso1() {
        FinanceScenarioFactory s = umCenarioBase();
        Lentes antes = Lentes.de(s, BASE_DATE);
        s.caso1DespesaPix();
        Lentes depois = Lentes.de(s, BASE_DATE);

        assertThat(depois.nubank().subtract(antes.nubank())).isEqualTo(m("-100.00"));
        assertThat(depois.consumo().get(OUT_26).subtract(antes.consumo().get(OUT_26))).isEqualTo(m("100.00"));
        assertThat(ConsumptionReport.of(s.records(), s.records().ledger(), OUT_26).expensesByMethod().get(com.moneylog.core.transaction.domain.PaymentMethod.PIX))
                .isEqualTo(m("100.00"));
        assertThat(depois.consumoTotal().subtract(antes.consumoTotal())).isEqualTo(m("100.00"));
        assertThat(alimentacao(s, OUT_26)).as("Alimentação +100 em out/26").isEqualTo(m("100.00"));
        assertThat(depois.patrimonio().subtract(antes.patrimonio())).isEqualTo(m("-100.00"));
    }

    @Test
    @DisplayName("caso 2: R$ 1.200 em 6x → caixa 0; +200 em cada mês out/26..mar/27 (out = 200, não 1.200); patrimônio −200 em out/26; parcelas futuras 1.000")
    void caso2() {
        FinanceScenarioFactory s = umCenarioBase();
        Lentes antes = Lentes.de(s, BASE_DATE);
        s.caso2CompraParcelada();
        Lentes depois = Lentes.de(s, BASE_DATE);

        assertThat(depois.inAccounts()).isEqualTo(antes.inAccounts());
        assertThat(depois.nubank()).isEqualTo(antes.nubank());
        for (int i = 0; i < 6; i++) {
            Competence c = OUT_26.plusMonths(i);
            assertThat(depois.consumo().get(c).subtract(antes.consumo().get(c))).as(c.toString()).isEqualTo(m("200.00"));
        }
        assertThat(depois.consumo().get(OUT_26.plusMonths(6))).isEqualTo(m("0.00"));
        assertThat(depois.consumo().get(OUT_26)).isEqualTo(m("200.00"));
        assertThat(depois.patrimonio().subtract(antes.patrimonio())).isEqualTo(m("-200.00"));
        assertThat(depois.parcelasFuturas()).isEqualTo(m("1000.00"));
        assertSemReceita(antes, depois);
    }

    @Test
    @DisplayName("caso 3: pagar a fatura de R$ 1.283 (parcela 1 de 200 + 1.083 à vista) em 05/11 → Nubank −1.283; consumo Δ 0; patrimônio Δ 0")
    void caso3() {
        FinanceScenarioFactory s = umCenarioBase();
        s.caso2CompraParcelada();
        s.compraAVista1083();
        LocalDate pagamento = LocalDate.of(2026, 11, 5);
        assertThat(s.chain().invoice(LocalDate.of(2026, 10, 25)).total()).isEqualTo(m("1283.00"));
        Lentes antes = Lentes.de(s, pagamento);
        s.caso3PagarFatura("1283.00");
        Lentes depois = Lentes.de(s, pagamento);

        assertThat(depois.nubank().subtract(antes.nubank())).isEqualTo(m("-1283.00"));
        assertSemConsumoNemReceita(antes, depois);
        assertThat(depois.patrimonio()).isEqualTo(antes.patrimonio());
        assertThat(s.chain().statusOf(LocalDate.of(2026, 10, 25), pagamento)).isEqualTo(com.moneylog.core.card.domain.InvoiceStatus.PAID);
        assertThat(s.chain().invoice(LocalDate.of(2026, 10, 25)).outstanding()).isEqualTo(m("0.00"));
        var parcelasNaFatura = s.chain().invoice(LocalDate.of(2026, 10, 25)).items().stream()
                .filter(i -> i.installmentId() != null).toList();
        assertThat(parcelasNaFatura).hasSize(2).allSatisfy(i -> {
            var parcela = s.transactions.stream().flatMap(t -> t.installments().stream()).filter(p -> p.id().equals(i.installmentId())).findFirst().orElseThrow();
            assertThat(parcela.statusFor(s.chain().statusOf(parcela.invoiceClosingDate(), pagamento)))
                    .isEqualTo(com.moneylog.core.card.domain.InstallmentStatus.PAID);
        });
    }

    @Test
    @DisplayName("caso 4: R$ 1.000 Nubank → XP → Nubank −1.000, XP +1.000, total 0; consumo 0; patrimônio 0")
    void caso4() {
        FinanceScenarioFactory s = umCenarioBase();
        Lentes antes = Lentes.de(s, BASE_DATE);
        s.caso4Transferencia();
        Lentes depois = Lentes.de(s, BASE_DATE);

        assertThat(depois.nubank().subtract(antes.nubank())).isEqualTo(m("-1000.00"));
        assertThat(depois.xp().subtract(antes.xp())).isEqualTo(m("1000.00"));
        assertThat(depois.inAccounts()).isEqualTo(antes.inAccounts());
        assertSemConsumoNemReceita(antes, depois);
        assertThat(depois.patrimonio()).isEqualTo(antes.patrimonio());
    }

    @Test
    @DisplayName("caso 5: investir R$ 10.000 → Nubank −10.000, investimentos +10.000; consumo 0; patrimônio 0")
    void caso5() {
        FinanceScenarioFactory s = umCenarioBase();
        Lentes antes = Lentes.de(s, BASE_DATE);
        s.caso5Investimento();
        Lentes depois = Lentes.de(s, BASE_DATE);

        assertThat(depois.nubank().subtract(antes.nubank())).isEqualTo(m("-10000.00"));
        assertThat(depois.inAccounts().subtract(antes.inAccounts())).isEqualTo(m("-10000.00"));
        assertThat(depois.investments().subtract(antes.investments())).isEqualTo(m("10000.00"));
        assertThat(depois.xpInvest()).isEqualTo(m("10000.00"));
        assertSemConsumoNemReceita(antes, depois);
        assertThat(depois.patrimonio()).isEqualTo(antes.patrimonio());
    }

    @Test
    @DisplayName("caso 6: emprestar R$ 300 ao João → caixa −300; consumo 0; patrimônio 0 (−300 caixa, +300 recebível)")
    void caso6() {
        FinanceScenarioFactory s = umCenarioBase();
        Lentes antes = Lentes.de(s, BASE_DATE);
        s.caso6Emprestimo();
        Lentes depois = Lentes.de(s, BASE_DATE);

        assertThat(depois.nubank().subtract(antes.nubank())).isEqualTo(m("-300.00"));
        assertSemConsumoNemReceita(antes, depois);
        assertThat(depois.patrimonio()).isEqualTo(antes.patrimonio());
        assertThat(NetWorth.at(s.records().ledger(), BASE_DATE).receivables()).isEqualTo(m("300.00"));
    }

    @Test
    @DisplayName("caso 7: João devolve R$ 300 → caixa +300; consumo 0 e receita 0; patrimônio 0 (recebível −300); dívida SETTLED")
    void caso7() {
        FinanceScenarioFactory s = umCenarioBase();
        s.caso6Emprestimo();
        Lentes antes = Lentes.de(s, BASE_DATE);
        s.caso7Devolucao();
        Lentes depois = Lentes.de(s, BASE_DATE);

        assertThat(depois.nubank().subtract(antes.nubank())).isEqualTo(m("300.00"));
        assertSemConsumoNemReceita(antes, depois);
        assertThat(depois.receita().get(OUT_26)).isEqualTo(m("0.00"));
        assertThat(depois.patrimonio()).isEqualTo(antes.patrimonio());
        assertThat(NetWorth.at(s.records().ledger(), BASE_DATE).receivables()).isEqualTo(m("0.00"));
        assertThat(s.debts.getFirst().status()).isEqualTo(DebtStatus.SETTLED);
    }

    @Test
    @DisplayName("caso 8: eu devo R$ 200 sem movimentação → caixa 0; consumo 0; patrimônio −200 (passivo +200)")
    void caso8() {
        FinanceScenarioFactory s = umCenarioBase();
        Lentes antes = Lentes.de(s, BASE_DATE);
        s.caso8DividaSemMovimentacao();
        Lentes depois = Lentes.de(s, BASE_DATE);

        assertThat(depois.inAccounts()).isEqualTo(antes.inAccounts());
        assertSemConsumoNemReceita(antes, depois);
        assertThat(depois.patrimonio().subtract(antes.patrimonio())).isEqualTo(m("-200.00"));
        assertThat(NetWorth.at(s.records().ledger(), BASE_DATE).payables()).isEqualTo(m("-200.00"));
    }

    @Test
    @DisplayName("caso 9: reembolso de R$ 50 do caso 1 → Nubank +50; Alimentação −50; patrimônio +50")
    void caso9() {
        FinanceScenarioFactory s = umCenarioBase();
        Transaction original = s.caso1DespesaPix();
        Lentes antes = Lentes.de(s, BASE_DATE);
        s.caso9Reembolso(original);
        Lentes depois = Lentes.de(s, BASE_DATE);

        assertThat(depois.nubank().subtract(antes.nubank())).isEqualTo(m("50.00"));
        assertThat(depois.consumo().get(OUT_26).subtract(antes.consumo().get(OUT_26))).isEqualTo(m("-50.00"));
        assertThat(depois.receita()).isEqualTo(antes.receita());
        assertThat(alimentacao(s, OUT_26)).as("Alimentação −50 sobre os +100 da despesa original").isEqualTo(m("50.00"));
        assertThat(alimentacao(s, OUT_26).subtract(Money.of("100.00"))).isEqualTo(m("-50.00"));
        assertThat(depois.patrimonio().subtract(antes.patrimonio())).isEqualTo(m("50.00"));
    }

    private static void assertSemReceita(Lentes antes, Lentes depois) {
        assertThat(depois.receita()).isEqualTo(antes.receita());
    }
}
