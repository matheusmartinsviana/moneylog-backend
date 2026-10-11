package com.moneylog.core.debt.domain;

import static com.moneylog.core.account.util.factory.AccountTestFactory.umaContaCorrente;
import static com.moneylog.core.debt.util.factory.DebtTestFactory.LOAN_DEBT_ID;
import static com.moneylog.core.debt.util.factory.DebtTestFactory.LOAN_TX_ID;
import static com.moneylog.core.debt.util.factory.DebtTestFactory.SETTLEMENT_TX_ID;
import static com.moneylog.core.debt.util.factory.DebtTestFactory.umEmprestimoAoJoao;
import static com.moneylog.core.debt.util.factory.DebtTestFactory.umHeader;
import static com.moneylog.core.debt.util.factory.DebtTestFactory.umaDividaComOPedro;
import static com.moneylog.core.util.Ids.OTHER_USER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.moneylog.core.debt.exception.DebtExceptions.DebtStateInconsistentException;
import com.moneylog.core.sk.exception.DomainException;
import com.moneylog.core.sk.ledger.BookRef;
import com.moneylog.core.sk.ledger.LedgerEntry;
import com.moneylog.core.sk.ledger.Posting;
import com.moneylog.core.sk.ledger.PostingSource;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.transaction.domain.DebtTransactions;

@DisplayName("Debt.restore (T45)")
class DebtRestoreTest {

    private static final LocalDate HOJE = LocalDate.of(2026, 10, 20);
    private static final UUID OUTRA_TX = UUID.fromString("00000000-0000-0000-0000-0000000e0b03");

    private static Debt recebivelQuitadoEmParte() {
        Debt debt = umEmprestimoAoJoao().debt();
        DebtTransactions.moneyReceived(umHeader(SETTLEMENT_TX_ID), Money.of("100.00"), umaContaCorrente(), debt);
        return debt;
    }

    private static Debt recebivelComQuitacaoEstornada() {
        Debt debt = recebivelQuitadoEmParte();
        debt.voidSettlement(SETTLEMENT_TX_ID);
        return debt;
    }

    private static Debt aPagarQuitada() {
        Debt debt = umaDividaComOPedro();
        DebtTransactions.debtPayment(umHeader(SETTLEMENT_TX_ID), Money.of("200.00"), umaContaCorrente(), debt);
        return debt;
    }

    private static Debt aPagarPerdoada() {
        Debt debt = umaDividaComOPedro();
        DebtTransactions.debtPayment(umHeader(SETTLEMENT_TX_ID), Money.of("50.00"), umaContaCorrente(), debt);
        debt.cancel("o Pedro perdoou", HOJE);
        return debt;
    }

    private static Debt registroEstornado() {
        Debt debt = umaDividaComOPedro();
        debt.voidRecord("registro errado", HOJE);
        return debt;
    }

    private static Debt origemEstornada() {
        Debt debt = umEmprestimoAoJoao().debt();
        debt.markOriginVoided(LOAN_TX_ID, "emprestei errado", HOJE);
        return debt;
    }

    static Stream<Arguments> dividas() {
        return Stream.of(
                Arguments.of("RECEIVABLE com movimentação, aberta", umEmprestimoAoJoao().debt()),
                Arguments.of("RECEIVABLE com quitação parcial", recebivelQuitadoEmParte()),
                Arguments.of("RECEIVABLE com quitação estornada", recebivelComQuitacaoEstornada()),
                Arguments.of("PAYABLE sem movimentação, aberta", umaDividaComOPedro()),
                Arguments.of("PAYABLE quitada", aPagarQuitada()),
                Arguments.of("PAYABLE perdoada (CANCELED)", aPagarPerdoada()),
                Arguments.of("registro estornado (VOIDED)", registroEstornado()),
                Arguments.of("origem estornada (VOIDED)", origemEstornada()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dividas")
    @DisplayName("CA2: ida e volta preserva status derivado, saldo, quitações e lançamentos")
    void deveReidratarOMesmoAgregado(String nome, Debt original) {
        Debt restored = Debt.restore(original.state(), original.settlements(), original.entries());

        assertThat(restored).isEqualTo(original).isNotSameAs(original);
        assertThat(restored.state()).isEqualTo(original.state());
        assertThat(restored.status()).isEqualTo(original.status());
        assertThat(restored.settled()).isEqualTo(original.settled());
        assertThat(restored.outstanding()).isEqualTo(original.outstanding());
        assertThat(restored.settlements()).containsExactlyElementsOf(original.settlements());
        assertThat(restored.entries()).containsExactlyElementsOf(original.entries());
        assertThat(restored.isOverdue(LocalDate.of(2026, 12, 1))).isEqualTo(original.isOverdue(LocalDate.of(2026, 12, 1)));
        assertThat(restored.book()).isEqualTo(original.book());
        assertThat(restored.closure()).isEqualTo(original.closure());
        assertThat(restored.closureDate()).isEqualTo(original.closureDate());
        assertThat(restored.closureReason()).isEqualTo(original.closureReason());
        assertThat(restored.toString()).isEqualTo(original.toString());
    }

    @Test
    @DisplayName("a dívida reidratada continua aceitando operações (quitar o saldo restante) como a original")
    void deveContinuarOperavel() {
        Debt original = recebivelQuitadoEmParte();
        Debt restored = Debt.restore(original.state(), original.settlements(), original.entries());

        restored.registerSettlement(OUTRA_TX, Money.of("200.00"));

        assertThat(restored.status()).isEqualTo(DebtStatus.SETTLED);
        assertThat(restored.outstanding()).isEqualTo(Money.zero(restored.principal().currency()));
        assertThat(original.status()).isEqualTo(DebtStatus.PARTIALLY_SETTLED);
    }

    @Nested
    @DisplayName("CA3: estado corrompido não é aceito em silêncio")
    class EstadoInconsistente {

        private final Debt aPagar = umaDividaComOPedro();
        private final Debt recebivel = umEmprestimoAoJoao().debt();

        private DebtState estado(Debt d, java.util.function.UnaryOperator<DebtStateBuilder> mudanca) {
            return mudanca.apply(new DebtStateBuilder(d.state())).build();
        }

        private void assertInconsistente(DebtState state, List<DebtSettlement> settlements, List<LedgerEntry> entries, String trecho) {
            assertThatThrownBy(() -> Debt.restore(state, settlements, entries))
                    .isInstanceOf(DebtStateInconsistentException.class)
                    .isInstanceOf(DomainException.class)
                    .hasFieldOrPropertyWithValue("code", "DEBT_STATE_INCONSISTENT")
                    .hasMessageContaining(trecho);
        }

        @Test
        @DisplayName("campos obrigatórios ausentes")
        void deveRecusarCamposObrigatorios() {
            assertInconsistente(estado(aPagar, b -> b.personId(null)), List.of(), aPagar.entries(), "obrigatórios");
            assertInconsistente(estado(aPagar, b -> b.direction(null)), List.of(), aPagar.entries(), "obrigatórios");
            assertInconsistente(estado(aPagar, b -> b.origin(null)), List.of(), aPagar.entries(), "obrigatórios");
            assertInconsistente(estado(aPagar, b -> b.date(null)), List.of(), aPagar.entries(), "obrigatórios");
        }

        @Test
        @DisplayName("principal zero, negativo, em outra moeda ou acima do teto")
        void deveRecusarPrincipalInvalido() {
            assertInconsistente(estado(aPagar, b -> b.principal(Money.of("0.00"))), List.of(), aPagar.entries(), "principal");
            assertInconsistente(estado(aPagar, b -> b.principal(Money.of("-1.00"))), List.of(), aPagar.entries(), "principal");
            assertInconsistente(estado(aPagar, b -> b.principal(Money.of("1000000000.00"))), List.of(), aPagar.entries(), "valor");
        }

        @Test
        @DisplayName("movimentação de origem só em WITH_MOVEMENT (e obrigatória nela)")
        void deveRecusarOrigemIncoerente() {
            assertInconsistente(estado(aPagar, b -> b.originTransactionId(LOAN_TX_ID)), List.of(), aPagar.entries(), "origem");
            assertInconsistente(estado(recebivel, b -> b.originTransactionId(null)), List.of(), recebivel.entries(), "origem");
        }

        @Test
        @DisplayName("encerramento sem data ou sem motivo, ou data/motivo sem encerramento")
        void deveRecusarEncerramentoIncompleto() {
            assertInconsistente(estado(aPagar, b -> b.closure(DebtClosure.VOIDED)), List.of(), aPagar.entries(), "encerramento");
            assertInconsistente(estado(aPagar, b -> b.closureDate(HOJE)), List.of(), aPagar.entries(), "encerramento");
            assertInconsistente(estado(aPagar, b -> b.closure(DebtClosure.VOIDED).closureDate(HOJE)), List.of(), aPagar.entries(),
                    "encerramento");
        }

        @Test
        @DisplayName("lançamentos de outra origem (outra dívida, tipo de origem de movimentação)")
        void deveRecusarLancamentosDeOutraOrigem() {
            List<LedgerEntry> deOutraDivida = aPagar.entries().stream()
                    .map(e -> new LedgerEntry(PostingSource.debtRecognition(LOAN_DEBT_ID), e.posting(), e.active())).toList();
            assertInconsistente(aPagar.state(), List.of(), deOutraDivida, "outra origem");

            List<LedgerEntry> deMovimentacao = aPagar.entries().stream()
                    .map(e -> new LedgerEntry(PostingSource.transaction(aPagar.id()), e.posting(), e.active())).toList();
            assertInconsistente(aPagar.state(), List.of(), deMovimentacao, "outra origem");
        }

        @Test
        @DisplayName("lançamentos de reconhecimento ou de perdão que não fecham em zero")
        void deveRecusarSomaDiferenteDeZero() {
            LedgerEntry primeiro = aPagar.entries().get(0);
            List<LedgerEntry> desbalanceados = List.of(new LedgerEntry(primeiro.source(),
                    new Posting(primeiro.posting().book(), Money.of("1.00"), primeiro.posting().effectiveDate()), true),
                    aPagar.entries().get(1));
            assertInconsistente(aPagar.state(), List.of(), desbalanceados, "não fecham em zero");

            Debt perdoada = aPagarPerdoada();
            List<LedgerEntry> perdaoDesbalanceado = new ArrayList<>(perdoada.entries());
            LedgerEntry perdao = perdaoDesbalanceado.get(perdaoDesbalanceado.size() - 1);
            perdaoDesbalanceado.set(perdaoDesbalanceado.size() - 1, new LedgerEntry(perdao.source(),
                    new Posting(BookRef.equity(), Money.of("0.01"), perdao.posting().effectiveDate()), true));
            assertInconsistente(perdoada.state(), perdoada.settlements(), perdaoDesbalanceado, "não fecham em zero");
        }

        @Test
        @DisplayName("reconhecimento só na dívida sem movimentação; perdão só na perdoada; estornada sem reconhecimento ativo")
        void deveRecusarLancamentosIncoerentesComAOrigemEOEncerramento() {
            assertInconsistente(recebivel.state(), List.of(), aPagar.entries().stream()
                    .map(e -> new LedgerEntry(PostingSource.debtRecognition(recebivel.id()), e.posting(), true)).toList(),
                    "sem movimentação");
            assertInconsistente(aPagar.state(), List.of(), List.of(), "sem movimentação");

            Debt perdoada = aPagarPerdoada();
            assertInconsistente(estado(perdoada, b -> b.closure(null).closureDate(null).closureReason(null)),
                    perdoada.settlements(), perdoada.entries(), "perdão");
            assertInconsistente(perdoada.state(), perdoada.settlements(), aPagar.entries(), "perdão");

            Debt estornada = registroEstornado();
            assertInconsistente(estornada.state(), List.of(), aPagar.entries(), "reconhecimento ativo");
            assertInconsistente(aPagar.state(), List.of(), estornada.entries(), "reconhecimento inativo");
        }

        @Test
        @DisplayName("Σ das quitações ativas passa do principal, quitação ativa repetida ou a origem como quitação")
        void deveRecusarQuitacoesInconsistentes() {
            assertInconsistente(aPagar.state(), List.of(new DebtSettlement(SETTLEMENT_TX_ID, Money.of("150.00"), true),
                    new DebtSettlement(OUTRA_TX, Money.of("100.00"), true)), aPagar.entries(), "passa do principal");
            assertInconsistente(aPagar.state(), List.of(new DebtSettlement(SETTLEMENT_TX_ID, Money.of("10.00"), true),
                    new DebtSettlement(SETTLEMENT_TX_ID, Money.of("10.00"), true)), aPagar.entries(), "repetida");
            assertInconsistente(recebivel.state(), List.of(new DebtSettlement(LOAN_TX_ID, Money.of("10.00"), true)),
                    recebivel.entries(), "origem");
            Debt ok = Debt.restore(aPagar.state(), List.of(new DebtSettlement(SETTLEMENT_TX_ID, Money.of("500.00"), false)),
                    aPagar.entries());
            assertThat(ok.status()).isEqualTo(DebtStatus.OPEN);
        }

        @Test
        @DisplayName("state, quitações e lançamentos nulos são erro de programação")
        void deveRecusarNulos() {
            assertThatThrownBy(() -> Debt.restore(null, List.of(), List.of())).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> Debt.restore(aPagar.state(), null, List.of())).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> Debt.restore(aPagar.state(), List.of(), null)).isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("o dono vem do estado (sem consultar a pessoa), mesmo sendo outro")
        void deveManterODonoDoEstado() {
            Debt restored = Debt.restore(estado(aPagar, b -> b.userId(OTHER_USER_ID)), List.of(), aPagar.entries());
            assertThat(restored.userId()).isEqualTo(OTHER_USER_ID);
        }
    }

    private static final class DebtStateBuilder {
        private DebtState s;

        DebtStateBuilder(DebtState s) {
            this.s = s;
        }

        DebtStateBuilder userId(UUID v) {
            s = new DebtState(s.id(), v, s.personId(), s.direction(), s.origin(), s.principal(), s.description(), s.date(),
                    s.expectedDate(), s.originTransactionId(), s.closure(), s.closureDate(), s.closureReason());
            return this;
        }

        DebtStateBuilder personId(UUID v) {
            s = new DebtState(s.id(), s.userId(), v, s.direction(), s.origin(), s.principal(), s.description(), s.date(),
                    s.expectedDate(), s.originTransactionId(), s.closure(), s.closureDate(), s.closureReason());
            return this;
        }

        DebtStateBuilder direction(DebtDirection v) {
            s = new DebtState(s.id(), s.userId(), s.personId(), v, s.origin(), s.principal(), s.description(), s.date(),
                    s.expectedDate(), s.originTransactionId(), s.closure(), s.closureDate(), s.closureReason());
            return this;
        }

        DebtStateBuilder origin(DebtOrigin v) {
            s = new DebtState(s.id(), s.userId(), s.personId(), s.direction(), v, s.principal(), s.description(), s.date(),
                    s.expectedDate(), s.originTransactionId(), s.closure(), s.closureDate(), s.closureReason());
            return this;
        }

        DebtStateBuilder principal(Money v) {
            s = new DebtState(s.id(), s.userId(), s.personId(), s.direction(), s.origin(), v, s.description(), s.date(),
                    s.expectedDate(), s.originTransactionId(), s.closure(), s.closureDate(), s.closureReason());
            return this;
        }

        DebtStateBuilder date(LocalDate v) {
            s = new DebtState(s.id(), s.userId(), s.personId(), s.direction(), s.origin(), s.principal(), s.description(), v,
                    s.expectedDate(), s.originTransactionId(), s.closure(), s.closureDate(), s.closureReason());
            return this;
        }

        DebtStateBuilder originTransactionId(UUID v) {
            s = new DebtState(s.id(), s.userId(), s.personId(), s.direction(), s.origin(), s.principal(), s.description(), s.date(),
                    s.expectedDate(), v, s.closure(), s.closureDate(), s.closureReason());
            return this;
        }

        DebtStateBuilder closure(DebtClosure v) {
            s = new DebtState(s.id(), s.userId(), s.personId(), s.direction(), s.origin(), s.principal(), s.description(), s.date(),
                    s.expectedDate(), s.originTransactionId(), v, s.closureDate(), s.closureReason());
            return this;
        }

        DebtStateBuilder closureDate(LocalDate v) {
            s = new DebtState(s.id(), s.userId(), s.personId(), s.direction(), s.origin(), s.principal(), s.description(), s.date(),
                    s.expectedDate(), s.originTransactionId(), s.closure(), v, s.closureReason());
            return this;
        }

        DebtStateBuilder closureReason(String v) {
            s = new DebtState(s.id(), s.userId(), s.personId(), s.direction(), s.origin(), s.principal(), s.description(), s.date(),
                    s.expectedDate(), s.originTransactionId(), s.closure(), s.closureDate(), v);
            return this;
        }

        DebtState build() {
            return s;
        }
    }
}
