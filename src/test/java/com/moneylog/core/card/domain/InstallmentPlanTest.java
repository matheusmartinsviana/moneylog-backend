package com.moneylog.core.card.domain;

import static com.moneylog.core.transaction.util.factory.TransactionTestFactory.compraNoCalendario;
import static com.moneylog.core.card.util.factory.CreditCardTestFactory.ROXINHO_ID;
import static com.moneylog.core.card.util.factory.CreditCardTestFactory.umCartao;
import static com.moneylog.core.card.util.factory.CreditCardTestFactory.umCartaoArquivado;
import static com.moneylog.core.category.util.factory.CategoryTestFactory.ALIMENTACAO_ID;
import static com.moneylog.core.category.util.factory.CategoryTestFactory.umaCategoriaDeDespesa;
import static com.moneylog.core.transaction.util.factory.TransactionTestFactory.NOW;
import static com.moneylog.core.transaction.util.factory.TransactionTestFactory.TX_ID;
import static com.moneylog.core.transaction.util.factory.TransactionTestFactory.umHeader;
import static com.moneylog.core.util.Ids.OTHER_USER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.moneylog.core.card.exception.CardExceptions.CardArchivedException;
import com.moneylog.core.card.exception.CardExceptions.CardNotFoundException;
import com.moneylog.core.card.exception.CardExceptions.InvalidInstallmentCountException;
import com.moneylog.core.card.exception.CardExceptions.InvalidInstallmentException;
import com.moneylog.core.sk.ledger.BookRef;
import com.moneylog.core.sk.ledger.BookType;
import com.moneylog.core.sk.ledger.LedgerEntry;
import com.moneylog.core.sk.ledger.Posting;
import com.moneylog.core.sk.ledger.PostingSource;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.money.MoneyExceptions.CurrencyMismatchException;
import com.moneylog.core.sk.money.MoneyExceptions.InvalidMoneyAllocationException;
import com.moneylog.core.sk.time.Competence;
import com.moneylog.core.transaction.domain.CardTransactions;
import com.moneylog.core.transaction.domain.Transaction;
import com.moneylog.core.transaction.domain.TransactionType;
import com.moneylog.core.transaction.domain.TransactionVoidPolicy;
import com.moneylog.core.transaction.domain.TransactionVoiding;
import com.moneylog.core.transaction.domain.VoidContext;
import com.moneylog.core.transaction.exception.TransactionExceptions.VoidContextMissingException;
import com.moneylog.core.transaction.exception.TransactionExceptions.VoidPolicyNotApplicableException;

@DisplayName("InstallmentPlan e CardTransactions.purchase (T5)")
class InstallmentPlanTest {

    private static final LocalDate COMPRA = LocalDate.of(2026, 10, 10);

    private static Transaction compra(String valor, int parcelas, LocalDate data) {
        return compraNoCalendario(umHeader(TX_ID, data), Money.of(valor), umCartao(), umaCategoriaDeDespesa(), parcelas);
    }

    @Test
    @DisplayName("CA4: 1.200 em 6x em 10/10/2026 = 6 parcelas de 200, competências 2026-10..2027-03, fechamentos 25/10/2026..25/03/2027 (ref. 2026-11..2027-04)")
    void deveGerarAsSeisParcelas() {
        Transaction tx = compra("1200.00", 6, COMPRA);
        List<TransactionInstallment> parcelas = tx.installments();
        InvoiceCalendar calendar = umCartao().calendar();

        assertThat(parcelas).hasSize(6);
        for (int k = 1; k <= 6; k++) {
            TransactionInstallment p = parcelas.get(k - 1);
            assertThat(p.number()).isEqualTo(k);
            assertThat(p.total()).isEqualTo(6);
            assertThat(p.amount()).isEqualTo(Money.of("200.00"));
            assertThat(p.competence()).isEqualTo(Competence.of(2026, 10).plusMonths(k - 1));
            assertThat(p.invoiceClosingDate()).isEqualTo(LocalDate.of(2026, 10, 25).plusMonths(k - 1));
            assertThat(calendar.periodFor(p.invoiceClosingDate().minusDays(1)).referenceMonth()).isEqualTo(YearMonth.of(2026, 11).plusMonths(k - 1));
            assertThat(p.lateEntry()).isFalse();
            assertThat(p.isCanceled()).isFalse();
            assertThat(p.transactionId()).isEqualTo(TX_ID);
        }
        assertThat(tx.type()).isEqualTo(TransactionType.CARD_PURCHASE);
        assertThat(tx.cardId()).isEqualTo(ROXINHO_ID);
    }

    @Test
    @DisplayName("CA5: por parcela EXPENSE(cat, comp_k) +200 / CARD −200 no dia 10 do mês k, origem com installmentId, nenhum ACCOUNT; out/26 = 200 e não 1.200")
    void deveGerarPostingsPorParcela() {
        Transaction tx = compra("1200.00", 6, COMPRA);

        assertThat(tx.postings()).hasSize(12).noneMatch(p -> p.bookType() == BookType.ACCOUNT);
        for (TransactionInstallment p : tx.installments()) {
            List<LedgerEntry> grupo = tx.entries().stream().filter(e -> p.id().equals(e.source().installmentId())).toList();
            assertThat(grupo).extracting(LedgerEntry::posting).containsExactly(
                    new Posting(BookRef.expense(ALIMENTACAO_ID), Money.of("200.00"), COMPRA.plusMonths(p.number() - 1)),
                    new Posting(BookRef.card(ROXINHO_ID), Money.of("-200.00"), COMPRA.plusMonths(p.number() - 1)));
            assertThat(grupo).allMatch(e -> e.source().equals(PostingSource.installment(TX_ID, p.id())));
        }
        Money outubro = Money.sum(tx.postings().stream()
                .filter(p -> p.bookType() == BookType.EXPENSE && p.competence().equals(Competence.of(2026, 10)))
                .map(Posting::amount).toList(), CurrencyCode.BRL);
        assertThat(outubro).isEqualTo(Money.of("200.00"));
    }

    @Test
    @DisplayName("CA6: 1.000 em 3x = 333,34 / 333,33 / 333,33 (S-3); 0,05 em 6x é rejeitado")
    void deveDividirComRestoNaPrimeira() {
        assertThat(compra("1000.00", 3, COMPRA).installments()).extracting(TransactionInstallment::amount)
                .containsExactly(Money.of("333.34"), Money.of("333.33"), Money.of("333.33"));
        assertThatThrownBy(() -> compra("0.05", 6, COMPRA)).isInstanceOf(InvalidMoneyAllocationException.class);
        assertThat(compra("0.01", 1, COMPRA).installments()).singleElement()
                .satisfies(p -> assertThat(p.amount()).isEqualTo(Money.of("0.01")));
    }

    @Test
    @DisplayName("CA6: compra em 31/01/2027 em 2x tem a parcela 2 em 28/02/2027")
    void deveAplicarClampNaDataEfetiva() {
        assertThat(compra("100.00", 2, LocalDate.of(2027, 1, 31)).installments().get(1).effectiveDate()).isEqualTo(LocalDate.of(2027, 2, 28));
    }

    @ParameterizedTest(name = "{0} parcelas")
    @ValueSource(ints = {0, 37, -1})
    @DisplayName("CA7: parcelas fora de 1..36 lançam InvalidInstallmentCountException (S-7)")
    void deveRejeitarQuantidadeInvalida(int parcelas) {
        assertThatThrownBy(() -> compra("1200.00", parcelas, COMPRA)).isInstanceOf(InvalidInstallmentCountException.class);
    }

    @Test
    @DisplayName("CA7: cartão arquivado lança CardArchivedException; cartão de outro usuário é não encontrado; moeda diferente é rejeitada")
    void deveValidarCartao() {
        assertThatThrownBy(() -> compraNoCalendario(umHeader(), Money.of("1.00"), umCartaoArquivado(), umaCategoriaDeDespesa(), 1))
                .isInstanceOf(CardArchivedException.class);
        CreditCard alheio = CreditCard.create(ROXINHO_ID, OTHER_USER_ID, "X", null, Money.of("1.00"), 25, 5, null, COMPRA);
        assertThatThrownBy(() -> compraNoCalendario(umHeader(), Money.of("1.00"), alheio, umaCategoriaDeDespesa(), 1))
                .isInstanceOf(CardNotFoundException.class);
        assertThatThrownBy(() -> compraNoCalendario(umHeader(), Money.of("1.00", CurrencyCode.of("USD")), umCartao(), umaCategoriaDeDespesa(), 1))
                .isInstanceOf(CurrencyMismatchException.class);
    }

    @Test
    @DisplayName("a policy padrão das contas não serve para CARD_PURCHASE (precisa da guarda do cartão)")
    void naoDeveEstornarCompraComPolicyDeConta() {
        Transaction tx = compra("100.00", 1, COMPRA);
        assertThatThrownBy(() -> TransactionVoidPolicy.accountTransactions().evaluate(tx))
                .isInstanceOf(VoidPolicyNotApplicableException.class);
        assertThatThrownBy(() -> TransactionVoiding.voidTransaction(tx, VoidContext.of("x", NOW, COMPRA)))
                .isInstanceOf(VoidContextMissingException.class);
        assertThat(tx.isActive()).isTrue();
    }

    @Test
    @DisplayName("parcela: invariantes, id determinístico e status derivado da fatura")
    void deveValidarParcela() {
        TransactionInstallment p = compra("100.00", 1, COMPRA).installments().getFirst();
        assertThat(p.id()).isEqualTo(TransactionInstallment.idFor(TX_ID, 1));
        assertThat(p.statusFor(InvoiceStatus.PAID)).isEqualTo(InstallmentStatus.PAID);
        assertThat(p.statusFor(InvoiceStatus.OVERDUE)).isEqualTo(InstallmentStatus.OVERDUE);
        assertThat(p.statusFor(InvoiceStatus.CLOSED)).isEqualTo(InstallmentStatus.PENDING);
        assertThat(p.statusFor(InvoiceStatus.OPEN)).isEqualTo(InstallmentStatus.PENDING);
        TransactionInstallment cancelada = p.cancel(NOW);
        assertThat(cancelada.statusFor(InvoiceStatus.PAID)).isEqualTo(InstallmentStatus.CANCELED);
        assertThat(cancelada.cancel(NOW.plusSeconds(1))).isSameAs(cancelada);
        assertThat(cancelada.canceledAt()).isEqualTo(NOW);
        assertThat(cancelada).isNotEqualTo(p);
        assertThat(cancelada.toString()).contains("CANCELED");
        assertThat(p.hashCode()).isEqualTo(cancelada.hashCode());
        assertThatThrownBy(() -> new TransactionInstallment(p.id(), TX_ID, 2, 1, Money.of("1.00"), COMPRA, COMPRA, false, null))
                .isInstanceOf(InvalidInstallmentException.class);
        assertThatThrownBy(() -> new TransactionInstallment(p.id(), TX_ID, 1, 1, Money.of("0.00"), COMPRA, COMPRA, false, null))
                .isInstanceOf(InvalidInstallmentException.class);
        assertThat(InvoiceStatus.PAID.isClosed()).isTrue();
        assertThat(InvoiceStatus.OPEN.isClosed()).isFalse();
    }
}
