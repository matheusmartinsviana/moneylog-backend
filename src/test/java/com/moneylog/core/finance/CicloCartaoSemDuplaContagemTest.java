package com.moneylog.core.finance;

import static com.moneylog.core.finance.util.FinanceScenarioFactory.umCenarioBase;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.DisplayName;

import com.moneylog.core.finance.domain.FinancialRecords;
import com.moneylog.core.finance.domain.Ledger;
import com.moneylog.core.sk.ledger.BookRef;
import com.moneylog.core.sk.ledger.BookType;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.time.Competence;
import com.moneylog.core.finance.util.FinanceScenarioFactory;
import com.moneylog.core.transaction.domain.Transaction;

@DisplayName("Ciclo do cartão sem dupla contagem (T8a CA2)")
class CicloCartaoSemDuplaContagemTest {

    @ParameterizedTest(name = "variante a maior/a menor = {0}")
    @ValueSource(booleans = {false, true})
    @DisplayName("1.200 em 6x com as 6 faturas pagas nos vencimentos: Σ consumo 1.200, Σ caixa −1.200, CARD = 0, nenhum mês > 200")
    void deveContarACompraUmaVezSo(boolean variante) {
        FinanceScenarioFactory s = umCenarioBase();
        Transaction compra = s.caso2CompraParcelada();
        for (int k = 0; k < 6; k++) {
            LocalDate closing = LocalDate.of(2026, 10, 25).plusMonths(k);
            LocalDate due = LocalDate.of(2026, 11, 5).plusMonths(k);
            String valor = !variante ? "200.00" : k == 0 ? "217.00" : k == 1 ? "183.00" : "200.00";
            s.pagarFatura(valor, closing, due);
        }
        FinancialRecords records = s.records();
        Ledger ledger = records.ledger();
        LocalDate fim = LocalDate.of(2027, 4, 30);

        Money consumoDaCompra = ledger.sum(e -> compra.id().equals(e.source().transactionId()) && e.posting().bookType() == BookType.EXPENSE);
        Money caixaDosPagamentos = ledger.sum(e -> e.posting().bookType() == BookType.ACCOUNT
                && !e.posting().effectiveDate().isAfter(fim)
                && s.transactions.stream().filter(t -> t.type() == com.moneylog.core.transaction.domain.TransactionType.INVOICE_PAYMENT)
                        .anyMatch(t -> t.id().equals(e.source().transactionId())));
        assertThat(consumoDaCompra).isEqualTo(Money.of("1200.00"));
        assertThat(caixaDosPagamentos).isEqualTo(Money.of("-1200.00"));
        assertThat(ledger.balance(BookRef.card(s.roxinho.id()), fim)).isEqualTo(Money.of("0.00"));
        assertThat(ledger.balanceAllDates(BookRef.card(s.roxinho.id()))).isEqualTo(Money.of("0.00"));
        for (Competence c : List.of(Competence.of(2026, 10), Competence.of(2026, 11), Competence.of(2026, 12), Competence.of(2027, 1),
                Competence.of(2027, 2), Competence.of(2027, 3), Competence.of(2027, 4))) {
            Money mes = ledger.sum(e -> compra.id().equals(e.source().transactionId()) && e.posting().bookType() == BookType.EXPENSE
                    && e.posting().competence().equals(c));
            assertThat(mes.isGreaterThan(Money.of("200.00"))).as(c.toString()).isFalse();
        }
        assertThat(s.chain().invoices()).allSatisfy(i -> assertThat(i.outstanding()).isEqualTo(Money.of("0.00")));
        assertThat(s.chain().creditBalance()).isEqualTo(Money.of("0.00"));
        assertThat(records.transactions()).hasSize(7);
    }
}
