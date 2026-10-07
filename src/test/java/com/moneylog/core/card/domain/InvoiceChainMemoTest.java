package com.moneylog.core.card.domain;

import static com.moneylog.core.card.util.factory.InvoiceScenario.DEC_CLOSING;
import static com.moneylog.core.card.util.factory.InvoiceScenario.NOV_CLOSING;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.moneylog.core.card.util.factory.InvoiceScenario;
import com.moneylog.core.sk.money.Money;

/**
 * B5: a cadeia é calculada uma vez por estado e reaproveitada por {@code invoice}/{@code statusOf}/{@code creditBalance};
 * as mutações invalidam o cálculo guardado e os resultados são os mesmos de uma cadeia nova.
 */
@DisplayName("Cadeia de faturas: cálculo memoizado e invalidado pelas mutações (B5)")
class InvoiceChainMemoTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 26);

    @Test
    @DisplayName("consultas repetidas devolvem a mesma fatura calculada (sem recalcular a cadeia por chamada)")
    void deveReaproveitarACadeia() {
        InvoiceChain chain = InvoiceScenario.comFaturaDe1283().chain();
        assertThat(chain.invoice(NOV_CLOSING)).isSameAs(chain.invoice(NOV_CLOSING));
        assertThat(chain.invoice(DEC_CLOSING)).isSameAs(chain.invoice(DEC_CLOSING));
        assertThat(chain.statusOf(NOV_CLOSING, TODAY)).isEqualTo(InvoiceStatus.CLOSED);
    }

    @Test
    @DisplayName("depois de registrar pagamento ou desativar um item, a consulta reflete o novo estado (igual a uma cadeia nova)")
    void deveInvalidarNasMutacoes() {
        InvoiceScenario s = InvoiceScenario.comFaturaDe1283();
        InvoiceChain chain = s.chain();
        assertThat(chain.invoice(NOV_CLOSING).outstanding()).isEqualTo(Money.of("1283.00"));

        chain.registerPayment(new InvoicePayment(java.util.UUID.randomUUID(), NOV_CLOSING, Money.of("1300.00"), TODAY, true), TODAY);

        assertThat(chain.invoice(NOV_CLOSING).statusAt(TODAY)).isEqualTo(InvoiceStatus.PAID);
        assertThat(chain.invoice(NOV_CLOSING).creditCarry()).isEqualTo(Money.of("17.00"));
        assertThat(chain.invoice(DEC_CLOSING).carryIn()).isEqualTo(Money.of("17.00"));
        assertThat(chain.creditBalance()).as("a fatura de dezembro absorve os 17,00").isEqualTo(Money.of("0.00"));

        chain.deactivateTransaction(InvoiceScenario.PURCHASE_1083_ID);
        assertThat(chain.invoice(NOV_CLOSING).total()).isEqualTo(Money.of("200.00"));
    }

    @Test
    @DisplayName("fatura antes da primeira movimentação é vazia e sem crédito; depois da última só transporta o crédito")
    void deveTratarForaDaCadeia() {
        InvoiceChain chain = InvoiceScenario.comFaturaDe1283().chain();
        assertThat(chain.invoice(LocalDate.of(2026, 9, 25)).total()).isEqualTo(Money.of("0.00"));
        assertThat(chain.invoice(LocalDate.of(2026, 9, 25)).carryIn()).isEqualTo(Money.of("0.00"));
        assertThat(chain.invoice(LocalDate.of(2027, 8, 25)).total()).isEqualTo(Money.of("0.00"));
        assertThat(chain.invoices()).isNotSameAs(chain.invoices());
    }
}
