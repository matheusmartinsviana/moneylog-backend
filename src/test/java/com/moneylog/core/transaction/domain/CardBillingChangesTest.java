package com.moneylog.core.transaction.domain;

import static com.moneylog.core.finance.util.FinanceScenarioFactory.umCenarioBase;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.moneylog.core.card.domain.BillingChange;
import com.moneylog.core.card.domain.CreditCard;
import com.moneylog.core.card.domain.CreditCardInvoice;
import com.moneylog.core.card.domain.InvoiceChain;
import com.moneylog.core.card.domain.TransactionInstallment;
import com.moneylog.core.card.exception.CardExceptions.InvoiceChainInconsistentException;
import com.moneylog.core.card.exception.CardExceptions.StaleBillingChangeException;
import com.moneylog.core.finance.domain.CommitmentSchedule;
import com.moneylog.core.finance.util.FinanceScenarioFactory;
import com.moneylog.core.sk.ledger.BookRef;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;

/**
 * C1 / BUG-QA-06 (D1 §2.5.1 regra 6, S-42): mudar o dia de fechamento realoca as parcelas e os créditos das faturas
 * FUTURE ("k-ésimo ciclo → k-ésimo ciclo"; item datado pela regra 2) e a cadeia nunca perde item em silêncio.
 *
 * <p>Cenário: Roxinho fecha 25 e vence 5. Em 15/10/2026 (fatura corrente fecha 25/10) o fechamento muda para 10 e o
 * vencimento para 20; a regra nova vale a partir das faturas que fecham em novembro.
 */
@DisplayName("Mudança de dia do cartão com realocação (C1, S-42)")
class CardBillingChangesTest {

    private static final LocalDate HOJE = LocalDate.of(2026, 10, 15);

    private static LocalDate d(String iso) {
        return LocalDate.parse(iso);
    }

    /** 6x de 1.200 em 10/10, 1.083 à vista em 12/10, compra datada de 20/11 (FUTURE), reembolso de 01/11 e antecipação. */
    private static FinanceScenarioFactory cenario() {
        FinanceScenarioFactory s = umCenarioBase();
        s.caso2CompraParcelada();
        s.compraAVista1083();
        s.transactions.add(CardTransactions.purchase(s.header(d("2026-11-20"), "Viagem"), Money.of("300.00"), s.roxinho, s.alimentacao,
                1, s.chain(), HOJE));
        s.transactions.add(CardTransactions.refundToCard(s.header(d("2026-11-01"), "Estorno"), Money.of("50.00"), s.roxinho,
                s.alimentacao, null, s.chain(), HOJE));
        s.pagarFatura("100.00", d("2026-10-25"), d("2026-10-14"));
        return s;
    }

    private static Money cardBalance(FinanceScenarioFactory s) {
        return s.records().ledger().balanceAllDates(BookRef.card(s.roxinho.id()));
    }

    private static Money outstandingMinusCarry(InvoiceChain chain) {
        Money outstanding = Money.sum(chain.invoices().stream().map(CreditCardInvoice::outstanding).toList(), CurrencyCode.BRL);
        return outstanding.subtract(chain.creditBalance());
    }

    @Test
    @DisplayName("Σ outstanding − creditCarry = −saldo CARD depois da troca 25 → 10, e os compromissos (+1.200 da compra) se mantêm")
    void deveManterAInvarianteDoCartao() {
        FinanceScenarioFactory s = cenario();
        Money cardAntes = cardBalance(s);
        assertThat(outstandingMinusCarry(s.chain())).isEqualTo(cardAntes.negate());
        Money compromissosAntes = CommitmentSchedule.of(s.records(), List.of(), HOJE).totalOut();

        CardBillingChanges.changeBillingDays(s.roxinho, s.transactions, 10, 20, HOJE);

        InvoiceChain depois = s.chain();
        assertThat(cardBalance(s)).isEqualTo(cardAntes);
        assertThat(outstandingMinusCarry(depois)).isEqualTo(cardAntes.negate());
        Money totals = Money.sum(depois.invoices().stream().map(CreditCardInvoice::total).toList(), CurrencyCode.BRL);
        Money paid = Money.sum(depois.invoices().stream().map(CreditCardInvoice::paid).toList(), CurrencyCode.BRL);
        assertThat(totals.subtract(paid)).isEqualTo(cardAntes.negate());
        assertThat(CommitmentSchedule.of(s.records(), List.of(), HOJE).totalOut()).isEqualTo(compromissosAntes);
    }

    @Test
    @DisplayName("parcelas no k-ésimo ciclo, item datado pela regra 2; fatura corrente, pagamento e compra da corrente não mudam")
    void deveRealocarCadaItem() {
        FinanceScenarioFactory s = cenario();
        Transaction parcelada = s.transactions.get(0);
        Transaction aVista = s.transactions.get(1);
        Transaction futura = s.transactions.get(2);
        Transaction reembolso = s.transactions.get(3);
        Transaction pagamento = s.transactions.get(4);

        List<Transaction> mudaram = CardBillingChanges.changeBillingDays(s.roxinho, s.transactions, 10, 20, HOJE);

        assertThat(parcelada.installments()).extracting(TransactionInstallment::invoiceClosingDate).containsExactly(
                d("2026-10-25"), d("2026-11-10"), d("2026-12-10"), d("2027-01-10"), d("2027-02-10"), d("2027-03-10"));
        assertThat(aVista.installments().getFirst().invoiceClosingDate()).isEqualTo(d("2026-10-25"));
        // 20/11 com fechamento 10: a primeira fatura que fecha depois de 20/11 é a de 10/12 (regra 2)
        assertThat(futura.installments().getFirst().invoiceClosingDate()).isEqualTo(d("2026-12-10"));
        assertThat(reembolso.invoiceClosingDate()).isEqualTo(d("2026-11-10"));
        assertThat(pagamento.invoiceClosingDate()).isEqualTo(d("2026-10-25"));
        assertThat(mudaram).containsExactlyInAnyOrder(parcelada, futura, reembolso);

        InvoiceChain chain = s.chain();
        assertThat(chain.invoice(d("2026-10-25")).dueDate()).isEqualTo(d("2026-11-05"));
        assertThat(chain.invoice(d("2026-11-10")).dueDate()).isEqualTo(d("2026-11-20"));
        assertThat(chain.invoice(d("2026-11-10")).total()).isEqualTo(Money.of("150.00")); // parcela 2 (200) − crédito (50)
        assertThat(chain.invoice(d("2026-12-10")).total()).isEqualTo(Money.of("500.00")); // parcela 3 (200) + viagem (300)
        assertThat(parcelada.postings()).hasSize(12);
        assertThat(s.roxinho.closingDay()).isEqualTo(10);
        assertThat(s.roxinho.dueDay()).isEqualTo(20);
    }

    @Test
    @DisplayName("parcelas canceladas também são realocadas (continuam apontando para fatura existente)")
    void deveRealocarParcelasCanceladas() {
        FinanceScenarioFactory s = umCenarioBase();
        Transaction compra = s.caso2CompraParcelada();
        CardTransactions.cancelRemainingInstallments(compra, s.chain(), HOJE, FinanceScenarioFactory.NOW);

        CardBillingChanges.changeBillingDays(s.roxinho, s.transactions, 10, 20, HOJE);

        assertThat(compra.installments()).filteredOn(TransactionInstallment::isCanceled).hasSize(6)
                .extracting(TransactionInstallment::invoiceClosingDate).contains(d("2026-11-10"), d("2027-03-10"));
        assertThat(outstandingMinusCarry(s.chain())).isEqualTo(cardBalance(s).negate());
    }

    @Test
    @DisplayName("mudar só o vencimento não move item nenhum")
    void naoDeveMoverNadaQuandoOFechamentoNaoMuda() {
        FinanceScenarioFactory s = cenario();
        assertThat(CardBillingChanges.changeBillingDays(s.roxinho, s.transactions, 25, 28, HOJE)).isEmpty();
        assertThat(s.chain().invoice(d("2026-11-25")).dueDate()).isEqualTo(d("2026-11-28"));
        assertThat(outstandingMinusCarry(s.chain())).isEqualTo(cardBalance(s).negate());
    }

    @Test
    @DisplayName("trocar a regra sem realocar não perde item em silêncio: a cadeia lança DomainException (invariante interna)")
    void naoDevePerderItemEmSilencio() {
        FinanceScenarioFactory s = umCenarioBase();
        s.caso2CompraParcelada();
        s.roxinho.changeBillingDays(10, 20, HOJE);

        assertThatThrownBy(s::chain)
                .isInstanceOf(InvoiceChainInconsistentException.class)
                .hasMessageContaining("2026-11-25");
    }

    @Test
    @DisplayName("mudança planejada sobre regras antigas ou de outro cartão é recusada; pagamento em fatura afetada é inconsistência")
    void deveRecusarMudancaVelha() {
        FinanceScenarioFactory s = umCenarioBase();
        BillingChange plano = s.roxinho.planBillingChange(10, 20, HOJE);
        s.roxinho.changeBillingDays(15, 25, HOJE);
        assertThatThrownBy(() -> s.roxinho.applyBillingChange(plano)).isInstanceOf(StaleBillingChangeException.class);

        CreditCard outro = CreditCard.create(java.util.UUID.fromString("00000000-0000-0000-0000-00000000d002"), s.roxinho.userId(),
                "Outro", null, Money.of("100.00"), 25, 5, null, HOJE);
        BillingChange doOutro = outro.planBillingChange(10, 20, HOJE);
        assertThatThrownBy(() -> s.roxinho.applyBillingChange(doOutro)).isInstanceOf(StaleBillingChangeException.class);

        BillingChange mudanca = umCenarioBase().roxinho.planBillingChange(10, 20, HOJE);
        assertThat(mudanca.keepPayment(d("2026-10-25"))).isEqualTo(d("2026-10-25"));
        assertThatThrownBy(() -> mudanca.keepPayment(d("2026-11-25"))).isInstanceOf(InvoiceChainInconsistentException.class);
        assertThatThrownBy(() -> mudanca.reallocateDated(d("2026-11-24"), d("2026-11-01")))
                .isInstanceOf(InvoiceChainInconsistentException.class);
    }

    @Test
    @DisplayName("se algum item não pode ser realocado, nem o cartão nem as movimentações mudam")
    void deveSerAtomica() {
        FinanceScenarioFactory s = umCenarioBase();
        Transaction compra = s.caso2CompraParcelada();
        Transaction pagamentoFuturoInconsistente = CardTransactions.invoicePayment(s.header(d("2026-10-14"), "Fatura"), Money.of("10.00"),
                s.nubank, s.roxinho, d("2026-10-25"), s.chain(), d("2026-10-14"));
        pagamentoFuturoInconsistente.reallocateInvoice(d("2026-11-25")); // simula um pagamento gravado numa fatura FUTURE
        s.transactions.add(pagamentoFuturoInconsistente);

        assertThatThrownBy(() -> CardBillingChanges.changeBillingDays(s.roxinho, s.transactions, 10, 20, HOJE))
                .isInstanceOf(InvoiceChainInconsistentException.class);
        assertThat(s.roxinho.closingDay()).isEqualTo(25);
        assertThat(compra.installments().get(1).invoiceClosingDate()).isEqualTo(d("2026-11-25"));
    }
}
