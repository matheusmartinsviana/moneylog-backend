package com.moneylog.core.card.domain;

import static com.moneylog.core.finance.util.FinanceScenarioFactory.umCenarioBase;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.moneylog.core.finance.util.FinanceScenarioFactory;
import com.moneylog.core.sk.ledger.BookRef;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.transaction.domain.CardBillingChanges;

/**
 * T12 CA4 (QA): mudança de dia de fechamento com parcelas já lançadas em faturas FUTURE (D1 §2.5.1 regra 6, S-42).
 */
@DisplayName("Mudança de fechamento com parcelas futuras (T12, QA)")
class MudancaDeDiaQaTest {

    /**
     * BUG-QA-06 (corrigido, C1): {@code CreditCard.changeBillingDays} só trocava a regra do calendário e as parcelas 2..6
     * ficavam com o {@code invoiceClosingDate} antigo, fora de todas as faturas (Σ total 200,00 contra CARD 1.200,00).
     * Agora a mudança passa por {@code CardBillingChanges}, que realoca as parcelas das faturas FUTURE no mesmo ciclo
     * (S-42), e a {@link InvoiceChain} recusa item com data fora do calendário em vez de perdê-lo.
     */
    @Test
    @DisplayName("BUG-QA-06: depois de trocar o fechamento de 25 para 10, Σ total das faturas continua = −CARD")
    void deveManterAsParcelasNasFaturasDepoisDaMudanca() {
        FinanceScenarioFactory s = umCenarioBase();
        s.caso2CompraParcelada();
        CardBillingChanges.changeBillingDays(s.roxinho, s.transactions, 10, 20, LocalDate.of(2026, 10, 15));
        InvoiceChain chain = s.chain();
        Money totals = Money.sum(chain.invoices().stream().map(CreditCardInvoice::total).toList(), CurrencyCode.BRL);
        Money card = s.records().ledger().balanceAllDates(BookRef.card(s.roxinho.id()));
        assertThat(totals).isEqualTo(card.negate());
    }
}
