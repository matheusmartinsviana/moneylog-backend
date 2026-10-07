package com.moneylog.core.transaction.domain;

import static com.moneylog.core.account.util.factory.AccountTestFactory.umaContaCorrente;
import static com.moneylog.core.account.util.factory.AccountTestFactory.umaContaDeInvestimento;
import static com.moneylog.core.account.util.factory.AccountTestFactory.umaContaSemSaldo;
import static com.moneylog.core.category.util.factory.CategoryTestFactory.umaCategoriaDeDespesa;
import static com.moneylog.core.category.util.factory.CategoryTestFactory.umaCategoriaDeReceita;
import static com.moneylog.core.debt.util.factory.DebtTestFactory.SETTLEMENT_TX_ID;
import static com.moneylog.core.debt.util.factory.DebtTestFactory.umEmprestimoAoJoao;
import static com.moneylog.core.transaction.util.factory.TransactionTestFactory.DATE;
import static com.moneylog.core.transaction.util.factory.TransactionTestFactory.NOW;
import static com.moneylog.core.transaction.util.factory.TransactionTestFactory.umHeader;
import static com.moneylog.core.transaction.util.factory.TransactionTestFactory.umaDespesaPix;
import static com.moneylog.core.util.Ids.USER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.moneylog.core.card.domain.TransactionInstallment;
import com.moneylog.core.card.util.factory.InvoiceScenario;
import com.moneylog.core.debt.domain.Debt;
import com.moneylog.core.debt.util.factory.PersonTestFactory;
import com.moneylog.core.sk.exception.DomainException;
import com.moneylog.core.sk.ledger.LedgerEntry;
import com.moneylog.core.sk.ledger.Posting;
import com.moneylog.core.sk.ledger.PostingSource;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.transaction.exception.TransactionExceptions.TransactionStateInconsistentException;

/** T45: reidratação de {@link Transaction} para o mapper da infra. */
@DisplayName("Transaction.restore (T45)")
class TransactionRestoreTest {

    private static final LocalDate HOJE = LocalDate.of(2026, 10, 10);
    private static final UUID ID_A = UUID.fromString("00000000-0000-0000-0000-0000000e0a01");
    private static final UUID ID_B = UUID.fromString("00000000-0000-0000-0000-0000000e0a02");
    private static final UUID ID_C = UUID.fromString("00000000-0000-0000-0000-0000000e0a03");

    private static TransactionHeader header(UUID id) {
        return umHeader(id, DATE);
    }

    private static Transaction despesaEstornada() {
        Transaction tx = umaDespesaPix();
        TransactionVoiding.voidTransaction(tx, VoidContext.of("lançada em duplicidade", NOW, HOJE)
                .withAccounts(List.of(umaContaCorrente())).withActiveRefunds(com.moneylog.core.sk.money.Money.of("0.00")));
        return tx;
    }

    private static Transaction compraEstornada() {
        InvoiceScenario scenario = InvoiceScenario.comCompraParcelada();
        Transaction compra = scenario.get(InvoiceScenario.PURCHASE_6X_ID);
        scenario.voidTx(compra, "devolvida", HOJE);
        return compra;
    }

    private static Transaction compraComParcelasCanceladas() {
        InvoiceScenario scenario = InvoiceScenario.comCompraParcelada();
        Transaction compra = scenario.get(InvoiceScenario.PURCHASE_6X_ID);
        // depois do fechamento de 25/10 a parcela 1/6 fica (fatura fechada); as 5 seguintes são canceladas
        CardTransactions.cancelRemainingInstallments(compra, scenario.chain(), LocalDate.of(2026, 10, 26), NOW);
        return compra;
    }

    /** Uma movimentação de cada tipo (e variações), incluindo estornadas e com parcelas canceladas. */
    static Stream<Arguments> movimentacoes() {
        Transaction despesa = umaDespesaPix();
        InvoiceScenario scenario = InvoiceScenario.comFaturaDe1283();
        Transaction pagamentoDeFatura = scenario.pay("500.00", InvoiceScenario.NOV_CLOSING, LocalDate.of(2026, 10, 20));
        Transaction reembolsoNoCartao = scenario.refund("50.00", LocalDate.of(2026, 10, 15), LocalDate.of(2026, 10, 15));
        Debt recebivel = umEmprestimoAoJoao().debt();
        Debt aPagar = com.moneylog.core.debt.util.factory.DebtTestFactory.umaDividaComOPedro();
        return Stream.of(
                Arguments.of("EXPENSE", despesa),
                Arguments.of("INCOME", AccountTransactions.income(header(ID_A), Money.of("3000.00"), umaContaCorrente(),
                        umaCategoriaDeReceita(), PaymentMethod.TRANSFER)),
                Arguments.of("TRANSFER", AccountTransactions.transfer(header(ID_A), Money.of("200.00"), umaContaCorrente(),
                        umaContaSemSaldo())),
                Arguments.of("INVESTMENT", AccountTransactions.investment(header(ID_A), Money.of("500.00"),
                        InvestmentDirection.CONTRIBUTION, umaContaCorrente(), umaContaDeInvestimento())),
                Arguments.of("ADJUSTMENT (correção)", AccountTransactions.adjustment(header(ID_A), Money.of("5.00"),
                        umaContaCorrente(), AdjustmentDirection.DECREASE, "tarifa não lançada")),
                Arguments.of("ADJUSTMENT (rendimento)", AccountTransactions.adjustment(header(ID_A), Money.of("12.34"),
                        umaContaDeInvestimento(), AdjustmentDirection.INCREASE, AdjustmentKind.YIELD, "rendimento do mês")),
                Arguments.of("REFUND em conta com origem", AccountTransactions.refundToAccount(header(ID_B), Money.of("40.00"),
                        umaContaCorrente(), umaCategoriaDeDespesa(), new RefundOrigin(despesa, Money.zero(despesa.amount().currency())))),
                Arguments.of("REFUND em conta sem origem", AccountTransactions.refundToAccount(header(ID_B), Money.of("40.00"),
                        umaContaCorrente(), umaCategoriaDeDespesa(), null)),
                Arguments.of("CARD_PURCHASE 6x", scenario.get(InvoiceScenario.PURCHASE_6X_ID)),
                Arguments.of("CARD_PURCHASE à vista", scenario.get(InvoiceScenario.PURCHASE_1083_ID)),
                Arguments.of("CARD_PURCHASE com parcelas canceladas", compraComParcelasCanceladas()),
                Arguments.of("INVOICE_PAYMENT", pagamentoDeFatura),
                Arguments.of("REFUND no cartão", reembolsoNoCartao),
                Arguments.of("LOAN_GIVEN", umEmprestimoAoJoao().transaction()),
                Arguments.of("MONEY_RECEIVED (devolução)", DebtTransactions.moneyReceived(
                        com.moneylog.core.debt.util.factory.DebtTestFactory.umHeader(SETTLEMENT_TX_ID), Money.of("100.00"),
                        umaContaCorrente(), recebivel).transaction()),
                Arguments.of("MONEY_RECEIVED (captação)", DebtTransactions.moneyBorrowed(header(ID_C), Money.of("500.00"),
                        umaContaCorrente(), new NewDebt(UUID.fromString("00000000-0000-0000-0000-0000000d0b09"),
                                PersonTestFactory.umPedro(), null, "adiantamento")).transaction()),
                Arguments.of("DEBT_PAYMENT", DebtTransactions.debtPayment(header(ID_C), Money.of("50.00"), umaContaCorrente(),
                        aPagar).transaction()),
                Arguments.of("EXPENSE estornada", despesaEstornada()),
                Arguments.of("CARD_PURCHASE estornada", compraEstornada()));
    }

    @Nested
    @DisplayName("CA1/CA2: ida e volta preserva o agregado")
    class IdaEVolta {

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.moneylog.core.transaction.domain.TransactionRestoreTest#movimentacoes")
        @DisplayName("restore(state(), entries()) devolve o mesmo agregado (id, status, version, entries com active, voidReason/voidedAt)")
        void deveReidratarOMesmoAgregado(String nome, Transaction original) {
            Transaction restored = Transaction.restore(original.state(), original.entries());

            assertThat(restored).isEqualTo(original);
            assertThat(restored).isNotSameAs(original);
            assertThat(restored.state()).isEqualTo(original.state());
            assertThat(restored.entries()).containsExactlyElementsOf(original.entries());
            assertThat(restored.postings()).containsExactlyElementsOf(original.postings());
            assertThat(restored.status()).isEqualTo(original.status());
            assertThat(restored.version()).isEqualTo(original.version());
            assertThat(restored.voidReason()).isEqualTo(original.voidReason());
            assertThat(restored.voidedAt()).isEqualTo(original.voidedAt());
            assertThat(restored.installments()).isEqualTo(original.installments());
            assertThat(restored.chargedAmount()).isEqualTo(original.chargedAmount());
            assertThat(restored.toString()).isEqualTo(original.toString());
        }

        @Test
        @DisplayName("a movimentação estornada volta estornada, com o voidedAt gravado (restore não consulta relógio nem guarda)")
        void deveManterOEstornoGravado() {
            Transaction estornada = despesaEstornada();
            Transaction restored = Transaction.restore(estornada.state(), estornada.entries());

            assertThat(restored.isActive()).isFalse();
            assertThat(restored.voidedAt()).isEqualTo(NOW);
            assertThat(restored.voidReason()).isEqualTo("lançada em duplicidade");
            assertThat(restored.postings()).isEmpty();
            assertThat(restored.entries()).noneMatch(LedgerEntry::active);
        }

        @Test
        @DisplayName("a compra com parcelas canceladas volta com só os lançamentos delas inativos")
        void deveManterParcelasCanceladas() {
            Transaction original = compraComParcelasCanceladas();
            Transaction restored = Transaction.restore(original.state(), original.entries());

            assertThat(restored.installments()).filteredOn(TransactionInstallment::isCanceled).hasSize(5);
            assertThat(restored.postings()).hasSameSizeAs(original.postings());
            assertThat(restored.entries()).filteredOn(e -> !e.active()).hasSize(10);
        }

        @Test
        @DisplayName("uma movimentação nova começa na versão 0 e restore preserva a versão gravada")
        void devePreservarAVersao() {
            Transaction nova = umaDespesaPix();
            assertThat(nova.version()).isZero();

            Transaction restored = Transaction.restore(com(nova.state(), 7L, nova.status(), null, null, nova.installments()),
                    nova.entries());

            assertThat(restored.version()).isEqualTo(7L);
            assertThat(restored.state().version()).isEqualTo(7L);
        }
    }

    @Nested
    @DisplayName("CA3: estado corrompido não é aceito em silêncio")
    class EstadoInconsistente {

        private final Transaction despesa = umaDespesaPix();

        private void assertInconsistente(TransactionState state, List<LedgerEntry> entries, String trecho) {
            assertThatThrownBy(() -> Transaction.restore(state, entries))
                    .isInstanceOf(TransactionStateInconsistentException.class)
                    .isInstanceOf(DomainException.class)
                    .hasFieldOrPropertyWithValue("code", "TRANSACTION_STATE_INCONSISTENT")
                    .hasMessageContaining(trecho);
        }

        @Test
        @DisplayName("lançamentos de outra origem (outra movimentação, outro tipo de origem)")
        void deveRecusarLancamentosDeOutraOrigem() {
            List<LedgerEntry> deOutra = despesa.entries().stream()
                    .map(e -> new LedgerEntry(PostingSource.transaction(ID_A), e.posting(), e.active())).toList();
            assertInconsistente(despesa.state(), deOutra, "outra origem");

            List<LedgerEntry> deAbertura = despesa.entries().stream()
                    .map(e -> new LedgerEntry(PostingSource.accountOpening(despesa.id()), e.posting(), e.active())).toList();
            assertInconsistente(despesa.state(), deAbertura, "outra origem");
        }

        @Test
        @DisplayName("lançamentos que não somam zero")
        void deveRecusarSomaDiferenteDeZero() {
            Posting p0 = despesa.entries().get(0).posting();
            List<LedgerEntry> desbalanceados = List.of(
                    new LedgerEntry(despesa.entries().get(0).source(), new Posting(p0.book(), Money.of("101.00"), p0.effectiveDate()), true),
                    despesa.entries().get(1));
            assertInconsistente(despesa.state(), desbalanceados, "Estado gravado da movimentação inconsistente");
        }

        @Test
        @DisplayName("sem lançamentos")
        void deveRecusarSemLancamentos() {
            assertInconsistente(despesa.state(), List.of(), "postings");
        }

        @Test
        @DisplayName("estornada sem voidedAt ou sem motivo")
        void deveRecusarEstornadaIncompleta() {
            Transaction estornada = despesaEstornada();
            assertInconsistente(com(estornada.state(), 0L, TransactionStatus.VOIDED, "motivo", null, List.of()),
                    estornada.entries(), "voidedAt");
            assertInconsistente(com(estornada.state(), 0L, TransactionStatus.VOIDED, null, NOW, List.of()),
                    estornada.entries(), "motivo");
            assertInconsistente(com(estornada.state(), 0L, TransactionStatus.VOIDED, "  ", NOW, List.of()),
                    estornada.entries(), "motivo");
        }

        @Test
        @DisplayName("estornada com lançamento ativo; ativa com dado de estorno; ativa com lançamento inativo")
        void deveRecusarStatusIncoerenteComLancamentos() {
            assertInconsistente(com(despesa.state(), 0L, TransactionStatus.VOIDED, "x", NOW, List.of()), despesa.entries(),
                    "lançamento ativo");
            Transaction estornada = despesaEstornada();
            assertInconsistente(com(estornada.state(), 0L, TransactionStatus.ACTIVE, null, null, List.of()),
                    estornada.entries(), "lançamento inativo");
            assertInconsistente(com(despesa.state(), 0L, TransactionStatus.ACTIVE, "x", null, List.of()), despesa.entries(),
                    "dados de estorno");
            assertInconsistente(com(despesa.state(), 0L, TransactionStatus.ACTIVE, null, NOW, List.of()), despesa.entries(),
                    "dados de estorno");
        }

        @Test
        @DisplayName("status ou versão inválidos")
        void deveRecusarStatusEVersaoInvalidos() {
            assertInconsistente(com(despesa.state(), 0L, null, null, null, List.of()), despesa.entries(), "status");
            assertInconsistente(com(despesa.state(), -1L, TransactionStatus.ACTIVE, null, null, List.of()), despesa.entries(),
                    "versão");
        }

        @Test
        @DisplayName("parcelas: em movimentação que não é compra, de outra movimentação, ou que não batem com os lançamentos")
        void deveRecusarParcelasIncoerentes() {
            Transaction compra = compraComParcelasCanceladas();
            TransactionInstallment parcela = compra.installments().get(0);
            assertInconsistente(com(despesa.state(), 0L, TransactionStatus.ACTIVE, null, null, List.of(parcela)), despesa.entries(),
                    "só a compra no cartão tem parcelas");

            TransactionInstallment alheia = new TransactionInstallment(parcela.id(), ID_A, parcela.number(), parcela.total(),
                    parcela.amount(), parcela.effectiveDate(), parcela.invoiceClosingDate(), parcela.lateEntry(), null);
            List<TransactionInstallment> comAlheia = new java.util.ArrayList<>(compra.installments());
            comAlheia.set(0, alheia);
            assertInconsistente(com(compra.state(), 0L, TransactionStatus.ACTIVE, null, null, comAlheia), compra.entries(),
                    "outra movimentação");

            List<TransactionInstallment> semCancelamento = compra.installments().stream()
                    .map(i -> new TransactionInstallment(i.id(), i.transactionId(), i.number(), i.total(), i.amount(),
                            i.effectiveDate(), i.invoiceClosingDate(), i.lateEntry(), null)).toList();
            assertInconsistente(com(compra.state(), 0L, TransactionStatus.ACTIVE, null, null, semCancelamento), compra.entries(),
                    "não está cancelada");
        }

        @Test
        @DisplayName("estornada com parcela não cancelada")
        void deveRecusarEstornadaComParcelaNaoCancelada() {
            Transaction estornada = compraEstornada();
            List<TransactionInstallment> abertas = estornada.installments().stream()
                    .map(i -> new TransactionInstallment(i.id(), i.transactionId(), i.number(), i.total(), i.amount(),
                            i.effectiveDate(), i.invoiceClosingDate(), i.lateEntry(), null)).toList();
            assertInconsistente(com(estornada.state(), 0L, TransactionStatus.VOIDED, estornada.voidReason(), estornada.voidedAt(),
                    abertas), estornada.entries(), "parcela não cancelada");
        }

        @Test
        @DisplayName("campos obrigatórios e valor: valor zero, tipo ausente e origem ausente viram a exceção da feature")
        void deveEmbrulharErrosDeConstrucao() {
            TransactionState s = despesa.state();
            TransactionState semValor = new TransactionState(s.id(), s.userId(), s.type(), Money.of("0.00"), s.date(),
                    s.description(), s.notes(), s.origin(), s.paymentMethod(), s.accountId(), s.counterpartAccountId(), s.cardId(),
                    s.categoryId(), s.debtId(), s.refundOfId(), s.investmentDirection(), s.adjustmentDirection(),
                    s.adjustmentKind(), s.reason(), s.invoiceClosingDate(), s.lateEntry(), s.installments(), s.status(),
                    s.voidReason(), s.voidedAt(), s.version());
            assertThatThrownBy(() -> Transaction.restore(semValor, despesa.entries()))
                    .isInstanceOf(TransactionStateInconsistentException.class).hasCauseInstanceOf(DomainException.class);

            TransactionState semOrigem = new TransactionState(s.id(), s.userId(), s.type(), s.amount(), s.date(),
                    s.description(), s.notes(), null, s.paymentMethod(), s.accountId(), s.counterpartAccountId(), s.cardId(),
                    s.categoryId(), s.debtId(), s.refundOfId(), s.investmentDirection(), s.adjustmentDirection(),
                    s.adjustmentKind(), s.reason(), s.invoiceClosingDate(), s.lateEntry(), s.installments(), s.status(),
                    s.voidReason(), s.voidedAt(), s.version());
            assertThatThrownBy(() -> Transaction.restore(semOrigem, despesa.entries()))
                    .isInstanceOf(TransactionStateInconsistentException.class);
        }

        @Test
        @DisplayName("state e entries nulos são erro de programação")
        void deveRecusarNulos() {
            assertThatThrownBy(() -> Transaction.restore(null, despesa.entries())).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> Transaction.restore(despesa.state(), null)).isInstanceOf(NullPointerException.class);
        }
    }

    @Test
    @DisplayName("CA4: restore não gera id: o id e o dono vêm do estado")
    void deveUsarOIdEODonoDoEstado() {
        Transaction original = umaDespesaPix();
        Transaction restored = Transaction.restore(original.state(), original.entries());
        assertThat(restored.id()).isEqualTo(original.id());
        assertThat(restored.userId()).isEqualTo(USER_ID);
    }

    /** Copia o estado trocando os campos que variam entre os cenários. */
    private static TransactionState com(TransactionState s, long version, TransactionStatus status, String voidReason,
                                        Instant voidedAt, List<TransactionInstallment> installments) {
        return new TransactionState(s.id(), s.userId(), s.type(), s.amount(), s.date(), s.description(), s.notes(), s.origin(),
                s.paymentMethod(), s.accountId(), s.counterpartAccountId(), s.cardId(), s.categoryId(), s.debtId(),
                s.refundOfId(), s.investmentDirection(), s.adjustmentDirection(), s.adjustmentKind(), s.reason(),
                s.invoiceClosingDate(), s.lateEntry(), installments, status, voidReason, voidedAt, version);
    }
}
