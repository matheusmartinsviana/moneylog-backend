package com.moneylog.core.debt.domain;

import static com.moneylog.core.account.util.factory.AccountTestFactory.NUBANK_ID;
import static com.moneylog.core.account.util.factory.AccountTestFactory.umaContaCorrente;
import static com.moneylog.core.account.util.factory.AccountTestFactory.umaContaDeInvestimento;
import static com.moneylog.core.debt.util.factory.DebtTestFactory.DATE;
import static com.moneylog.core.debt.util.factory.DebtTestFactory.EXPECTED;
import static com.moneylog.core.debt.util.factory.DebtTestFactory.LOAN_DEBT_ID;
import static com.moneylog.core.debt.util.factory.DebtTestFactory.LOAN_TX_ID;
import static com.moneylog.core.debt.util.factory.DebtTestFactory.PAYABLE_DEBT_ID;
import static com.moneylog.core.debt.util.factory.DebtTestFactory.SETTLEMENT_TX_ID;
import static com.moneylog.core.debt.util.factory.DebtTestFactory.umEmprestimoAoJoao;
import static com.moneylog.core.debt.util.factory.DebtTestFactory.umHeader;
import static com.moneylog.core.debt.util.factory.DebtTestFactory.umaDividaComOPedro;
import static com.moneylog.core.debt.util.factory.PersonTestFactory.umJoao;
import static com.moneylog.core.debt.util.factory.PersonTestFactory.umPedro;
import static com.moneylog.core.transaction.util.factory.TransactionTestFactory.NOW;
import static com.moneylog.core.util.Ids.OTHER_USER_ID;
import static com.moneylog.core.util.Ids.USER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.UUID;
import java.util.function.Consumer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.moneylog.core.debt.exception.DebtExceptions.DebtDirectionMismatchException;
import com.moneylog.core.debt.exception.DebtExceptions.DebtFieldRequiredException;
import com.moneylog.core.debt.exception.DebtExceptions.DebtNotFoundException;
import com.moneylog.core.debt.exception.DebtExceptions.DebtOperationRefusedException;
import com.moneylog.core.debt.exception.DebtExceptions.DebtReasonRequiredException;
import com.moneylog.core.debt.exception.DebtExceptions.InvalidDebtPrincipalException;
import com.moneylog.core.debt.exception.DebtExceptions.PersonArchiveStateException;
import com.moneylog.core.debt.exception.DebtExceptions.PersonArchivedException;
import com.moneylog.core.debt.exception.DebtExceptions.PersonNameRequiredException;
import com.moneylog.core.debt.exception.DebtExceptions.PersonNotFoundException;
import com.moneylog.core.debt.exception.DebtExceptions.SettlementExceedsOutstandingException;
import com.moneylog.core.sk.exception.DomainException;
import com.moneylog.core.sk.ledger.BookRef;
import com.moneylog.core.sk.ledger.BookType;
import com.moneylog.core.sk.ledger.LedgerEntry;
import com.moneylog.core.sk.ledger.Posting;
import com.moneylog.core.sk.ledger.PostingSource;
import com.moneylog.core.sk.ledger.PostingSourceType;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.transaction.domain.DebtOperation;
import com.moneylog.core.transaction.domain.DebtTransactionVoidPolicy;
import com.moneylog.core.transaction.domain.DebtTransactions;
import com.moneylog.core.transaction.domain.TransactionVoiding;
import com.moneylog.core.transaction.domain.VoidContext;
import com.moneylog.core.transaction.domain.NewDebt;
import com.moneylog.core.transaction.domain.Transaction;
import com.moneylog.core.transaction.domain.TransactionStatus;
import com.moneylog.core.transaction.domain.TransactionType;
import com.moneylog.core.transaction.exception.TransactionExceptions.AccountNotCashException;
import com.moneylog.core.transaction.exception.TransactionExceptions.TransactionVoidRefusedException;
import com.moneylog.core.transaction.exception.TransactionExceptions.VoidPolicyNotApplicableException;

@DisplayName("Pessoas, dívidas e empréstimos (T7)")
class DebtTest {

    private static final LocalDate HOJE = LocalDate.of(2026, 10, 20);
    private static final UUID SECOND_TX = UUID.fromString("00000000-0000-0000-0000-0000000e0b03");

    private static DebtOperation devolucao(Debt debt, String valor) {
        return DebtTransactions.moneyReceived(umHeader(SECOND_TX), Money.of(valor), umaContaCorrente(), debt);
    }

    @Nested
    @DisplayName("com movimentação")
    class ComMovimentacao {

        @Test
        @DisplayName("CA1: LOAN_GIVEN de 300 cria junto a dívida RECEIVABLE WITH_MOVEMENT; RECEIVABLE +300 / ACCOUNT −300, sem EXPENSE")
        void deveCriarEmprestimoComDivida() {
            DebtOperation op = umEmprestimoAoJoao();
            Debt debt = op.debt();
            Transaction tx = op.transaction();

            assertThat(debt.origin()).isEqualTo(DebtOrigin.WITH_MOVEMENT);
            assertThat(debt.direction()).isEqualTo(DebtDirection.RECEIVABLE);
            assertThat(debt.originTransactionId()).isEqualTo(tx.id()).isEqualTo(LOAN_TX_ID);
            assertThat(debt.principal()).isEqualTo(Money.of("300.00"));
            assertThat(debt.expectedDate()).isEqualTo(EXPECTED);
            assertThat(debt.entries()).isEmpty();
            assertThat(tx.type()).isEqualTo(TransactionType.LOAN_GIVEN);
            assertThat(tx.debtId()).isEqualTo(LOAN_DEBT_ID);
            assertThat(tx.postings()).containsExactly(
                    new Posting(BookRef.receivable(LOAN_DEBT_ID), Money.of("300.00"), DATE),
                    new Posting(BookRef.account(NUBANK_ID), Money.of("-300.00"), DATE));
            assertThat(tx.postings()).noneMatch(p -> p.bookType().isResult());
            assertThat(debt.status()).isEqualTo(DebtStatus.OPEN);
        }

        @Test
        @DisplayName("CA2: MONEY_RECEIVED de 300 gera ACCOUNT +300 / RECEIVABLE −300, sem INCOME, e SETTLED; com 100 é PARTIALLY_SETTLED com saldo 200")
        void deveQuitarSemReceita() {
            Debt total = umEmprestimoAoJoao().debt();
            Transaction tx = devolucao(total, "300.00").transaction();
            assertThat(tx.postings()).containsExactly(
                    new Posting(BookRef.account(NUBANK_ID), Money.of("300.00"), DATE),
                    new Posting(BookRef.receivable(LOAN_DEBT_ID), Money.of("-300.00"), DATE));
            assertThat(tx.postings()).noneMatch(p -> p.bookType() == BookType.INCOME);
            assertThat(total.status()).isEqualTo(DebtStatus.SETTLED);
            assertThat(total.outstanding()).isEqualTo(Money.of("0.00"));

            Debt parcial = umEmprestimoAoJoao().debt();
            devolucao(parcial, "100.00");
            assertThat(parcial.status()).isEqualTo(DebtStatus.PARTIALLY_SETTLED);
            assertThat(parcial.outstanding()).isEqualTo(Money.of("200.00"));
        }

        @Test
        @DisplayName("CA5: captação de 500 gera ACCOUNT +500 / PAYABLE −500, sem INCOME (S-6)")
        void deveRegistrarCaptacao() {
            UUID debtId = UUID.fromString("00000000-0000-0000-0000-0000000d0b09");
            DebtOperation op = DebtTransactions.moneyBorrowed(umHeader(SECOND_TX), Money.of("500.00"), umaContaCorrente(),
                    new NewDebt(debtId, umPedro(), null, null));

            assertThat(op.debt().direction()).isEqualTo(DebtDirection.PAYABLE);
            assertThat(op.transaction().type()).isEqualTo(TransactionType.MONEY_RECEIVED);
            assertThat(op.transaction().postings()).containsExactly(
                    new Posting(BookRef.payable(debtId), Money.of("-500.00"), DATE),
                    new Posting(BookRef.account(NUBANK_ID), Money.of("500.00"), DATE));
        }

        @Test
        @DisplayName("CA6: direção errada lança DebtDirectionMismatchException; quitação acima do saldo é rejeitada; segunda origem dá DEBT_ALREADY_HAS_ORIGIN")
        void deveValidarLigacoes() {
            Debt recebivel = umEmprestimoAoJoao().debt();
            Debt passivo = umaDividaComOPedro();

            assertThatThrownBy(() -> DebtTransactions.loanGiven(umHeader(SECOND_TX), Money.of("1.00"), umaContaCorrente(), passivo))
                    .isInstanceOf(DebtDirectionMismatchException.class);
            assertThatThrownBy(() -> DebtTransactions.debtPayment(umHeader(SECOND_TX), Money.of("1.00"), umaContaCorrente(), recebivel))
                    .isInstanceOf(DebtDirectionMismatchException.class);
            assertThatThrownBy(() -> devolucao(passivo, "1.00")).isInstanceOf(DebtDirectionMismatchException.class);
            assertThatThrownBy(() -> devolucao(recebivel, "300.01")).isInstanceOf(SettlementExceedsOutstandingException.class);
            assertThat(recebivel.settlements()).isEmpty();
            assertThatThrownBy(() -> DebtTransactions.loanGiven(umHeader(SECOND_TX), Money.of("50.00"), umaContaCorrente(), recebivel))
                    .isInstanceOf(DebtOperationRefusedException.class)
                    .extracting(e -> ((DomainException) e).code()).isEqualTo("DEBT_ALREADY_HAS_ORIGIN");
            Debt semMovimentacao = Debt.withoutMovement(UUID.randomUUID(), USER_ID, umJoao(), DebtDirection.RECEIVABLE,
                    Money.of("10.00"), DATE, null, null);
            assertThatThrownBy(() -> DebtTransactions.loanGiven(umHeader(SECOND_TX), Money.of("1.00"), umaContaCorrente(), semMovimentacao))
                    .extracting(e -> ((DomainException) e).code()).isEqualTo("DEBT_ALREADY_HAS_ORIGIN");
        }

        @Test
        @DisplayName("contas devem ser CASH; dívida e pessoa de outro usuário são não encontradas; dívida é obrigatória")
        void deveValidarReferencias() {
            assertThatThrownBy(() -> DebtTransactions.loanGiven(umHeader(SECOND_TX), Money.of("1.00"), umaContaDeInvestimento(),
                    new NewDebt(UUID.randomUUID(), umJoao(), null, null))).isInstanceOf(AccountNotCashException.class);
            Person alheia = Person.create(UUID.randomUUID(), OTHER_USER_ID, "X", null);
            assertThatThrownBy(() -> DebtTransactions.loanGiven(umHeader(SECOND_TX), Money.of("1.00"), umaContaCorrente(),
                    new NewDebt(UUID.randomUUID(), alheia, null, null))).isInstanceOf(PersonNotFoundException.class);
            Debt alheiaDivida = Debt.withoutMovement(UUID.randomUUID(), OTHER_USER_ID, alheia, DebtDirection.PAYABLE,
                    Money.of("1.00"), DATE, null, null);
            assertThatThrownBy(() -> DebtTransactions.debtPayment(umHeader(SECOND_TX), Money.of("1.00"), umaContaCorrente(), alheiaDivida))
                    .isInstanceOf(DebtNotFoundException.class);
            assertThatThrownBy(() -> DebtTransactions.debtPayment(umHeader(SECOND_TX), Money.of("1.00"), umaContaCorrente(), null))
                    .isInstanceOf(DebtFieldRequiredException.class);
        }

        @Test
        @DisplayName("CA9: estornar a quitação devolve o status a OPEN com saldo 300")
        void deveRecalcularStatusAoEstornarQuitacao() {
            Debt debt = umEmprestimoAoJoao().debt();
            Transaction quitacao = devolucao(debt, "300.00").transaction();
            assertThat(debt.status()).isEqualTo(DebtStatus.SETTLED);

            TransactionVoiding.voidTransaction(quitacao, VoidContext.of("registrado errado", NOW, HOJE).withDebt(debt));

            assertThat(quitacao.status()).isEqualTo(TransactionStatus.VOIDED);
            assertThat(debt.status()).isEqualTo(DebtStatus.OPEN);
            assertThat(debt.outstanding()).isEqualTo(Money.of("300.00"));
        }

        @Test
        @DisplayName("CA9: origem com quitação ativa é recusada com DEBT_HAS_SETTLEMENTS e nada muda; sem quitações, a dívida fica VOIDED")
        void deveGuardarEstornoDaOrigem() {
            DebtOperation op = umEmprestimoAoJoao();
            Debt debt = op.debt();
            devolucao(debt, "100.00");

            assertThatThrownBy(() -> TransactionVoiding.voidTransaction(op.transaction(), VoidContext.of("erro", NOW, HOJE).withDebt(debt)))
                    .isInstanceOf(TransactionVoidRefusedException.class)
                    .extracting(e -> ((DomainException) e).code()).isEqualTo("DEBT_HAS_SETTLEMENTS");
            assertThat(op.transaction().isActive()).isTrue();
            assertThat(debt.status()).isEqualTo(DebtStatus.PARTIALLY_SETTLED);

            DebtOperation limpo = umEmprestimoAoJoao();
            TransactionVoiding.voidTransaction(limpo.transaction(), VoidContext.of("erro", NOW, HOJE).withDebt(limpo.debt()));
            assertThat(limpo.transaction().postings()).isEmpty();
            assertThat(limpo.debt().status()).isEqualTo(DebtStatus.VOIDED);
            assertThat(limpo.debt().closureReason()).isEqualTo("erro");
            assertThat(limpo.debt().closureDate()).isEqualTo(HOJE);
            assertThat(limpo.debt().outstanding()).isEqualTo(Money.of("0.00"));
        }

        @Test
        @DisplayName("a policy de dívida não serve para movimentação de outra dívida")
        void deveRecusarPolicyDeOutraDivida() {
            Transaction tx = umEmprestimoAoJoao().transaction();
            assertThatThrownBy(() -> new DebtTransactionVoidPolicy(umaDividaComOPedro()).evaluate(tx))
                    .isInstanceOf(VoidPolicyNotApplicableException.class);
            assertThatThrownBy(() -> umEmprestimoAoJoao().debt().markOriginVoided(SECOND_TX, "x", HOJE))
                    .isInstanceOf(DebtNotFoundException.class);
            assertThatThrownBy(() -> umEmprestimoAoJoao().debt().voidSettlement(SECOND_TX))
                    .isInstanceOf(DebtNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("sem movimentação")
    class SemMovimentacao {

        @Test
        @DisplayName("CA3: PAYABLE 200 sem movimentação gera PAYABLE −200 / EQUITY +200 com origem DEBT_RECOGNITION, sem ACCOUNT nem EXPENSE")
        void deveReconhecerContraEquity() {
            Debt debt = umaDividaComOPedro();

            assertThat(debt.entries()).extracting(LedgerEntry::posting).containsExactly(
                    new Posting(BookRef.payable(PAYABLE_DEBT_ID), Money.of("-200.00"), DATE),
                    new Posting(BookRef.equity(), Money.of("200.00"), DATE));
            assertThat(debt.entries()).allMatch(e -> e.source().equals(PostingSource.debtRecognition(PAYABLE_DEBT_ID)) && e.active());
            assertThat(debt.originTransactionId()).isNull();
            assertThat(debt.origin()).isEqualTo(DebtOrigin.WITHOUT_MOVEMENT);
        }

        @Test
        @DisplayName("RECEIVABLE sem movimentação gera RECEIVABLE +X / EQUITY −X")
        void deveReconhecerRecebivel() {
            Debt debt = Debt.withoutMovement(LOAN_DEBT_ID, USER_ID, umJoao(), DebtDirection.RECEIVABLE, Money.of("50.00"), DATE, null, null);
            assertThat(debt.entries()).extracting(LedgerEntry::posting).containsExactly(
                    new Posting(BookRef.receivable(LOAN_DEBT_ID), Money.of("50.00"), DATE),
                    new Posting(BookRef.equity(), Money.of("-50.00"), DATE));
        }

        @Test
        @DisplayName("CA4: DEBT_PAYMENT de 200 gera PAYABLE +200 / ACCOUNT −200 e a dívida fica SETTLED")
        void devePagarADivida() {
            Debt debt = umaDividaComOPedro();
            Transaction tx = DebtTransactions.debtPayment(umHeader(SETTLEMENT_TX_ID), Money.of("200.00"), umaContaCorrente(), debt).transaction();

            assertThat(tx.postings()).containsExactly(
                    new Posting(BookRef.account(NUBANK_ID), Money.of("-200.00"), DATE),
                    new Posting(BookRef.payable(PAYABLE_DEBT_ID), Money.of("200.00"), DATE));
            assertThat(debt.status()).isEqualTo(DebtStatus.SETTLED);
        }

        @Test
        @DisplayName("CA10: voidRecord sem quitações desativa DEBT_RECOGNITION e fica VOIDED; com quitação ativa, DEBT_HAS_SETTLEMENTS")
        void deveEstornarORegistro() {
            Debt debt = umaDividaComOPedro();
            debt.voidRecord("registro errado", HOJE);
            assertThat(debt.entries()).noneMatch(LedgerEntry::active);
            assertThat(debt.status()).isEqualTo(DebtStatus.VOIDED);

            Debt comQuitacao = umaDividaComOPedro();
            DebtTransactions.debtPayment(umHeader(SETTLEMENT_TX_ID), Money.of("50.00"), umaContaCorrente(), comQuitacao);
            assertThatThrownBy(() -> comQuitacao.voidRecord("x", HOJE))
                    .extracting(e -> ((DomainException) e).code()).isEqualTo("DEBT_HAS_SETTLEMENTS");
            assertThat(comQuitacao.entries()).allMatch(LedgerEntry::active);

            assertThatThrownBy(() -> umEmprestimoAoJoao().debt().voidRecord("x", HOJE))
                    .extracting(e -> ((DomainException) e).code()).isEqualTo("DEBT_HAS_ORIGIN_TRANSACTION");
        }
    }

    @Nested
    @DisplayName("perdão e atraso")
    class PerdaoEAtraso {

        @Test
        @DisplayName("CA7: perdão de saldo 200 fica CANCELED e zera com DEBT_CANCELLATION contra EQUITY; depois, quitação e estorno de quitação dão DEBT_CLOSED")
        void devePerdoarSemDespesaNemReceita() {
            Debt debt = umEmprestimoAoJoao().debt();
            Transaction quitacao = devolucao(debt, "100.00").transaction();

            debt.cancel("perdão", HOJE);

            assertThat(debt.status()).isEqualTo(DebtStatus.CANCELED);
            assertThat(debt.outstanding()).isEqualTo(Money.of("0.00"));
            assertThat(debt.entries()).extracting(LedgerEntry::posting).containsExactly(
                    new Posting(BookRef.receivable(LOAN_DEBT_ID), Money.of("-200.00"), HOJE),
                    new Posting(BookRef.equity(), Money.of("200.00"), HOJE));
            assertThat(debt.entries()).allMatch(e -> e.source().type() == PostingSourceType.DEBT_CANCELLATION);
            assertThatThrownBy(() -> devolucao(debt, "1.00")).extracting(e -> ((DomainException) e).code()).isEqualTo("DEBT_CLOSED");
            assertThatThrownBy(() -> TransactionVoiding.voidTransaction(quitacao, VoidContext.of("x", NOW, HOJE).withDebt(debt)))
                    .extracting(e -> ((DomainException) e).code()).isEqualTo("DEBT_CLOSED");
            assertThat(quitacao.isActive()).isTrue();
        }

        @Test
        @DisplayName("perdão de passivo: PAYABLE +s / EQUITY −s")
        void devePerdoarPassivo() {
            Debt debt = umaDividaComOPedro();
            debt.cancel("o Pedro perdoou", HOJE);
            assertThat(debt.entries()).filteredOn(e -> e.source().type() == PostingSourceType.DEBT_CANCELLATION)
                    .extracting(LedgerEntry::posting).containsExactly(
                            new Posting(BookRef.payable(PAYABLE_DEBT_ID), Money.of("200.00"), HOJE),
                            new Posting(BookRef.equity(), Money.of("-200.00"), HOJE));
        }

        @Test
        @DisplayName("CA8: isOverdue só com previsão passada e saldo > 0")
        void deveCalcularAtraso() {
            LocalDate depois = EXPECTED.plusDays(1);
            Debt aberta = umaDividaComOPedro();
            assertThat(aberta.isOverdue(depois)).isTrue();
            assertThat(aberta.isOverdue(EXPECTED)).isFalse();

            Debt quitada = umaDividaComOPedro();
            DebtTransactions.debtPayment(umHeader(SETTLEMENT_TX_ID), Money.of("200.00"), umaContaCorrente(), quitada);
            assertThat(quitada.isOverdue(depois)).isFalse();
            Debt perdoada = umaDividaComOPedro();
            perdoada.cancel("x", HOJE);
            assertThat(perdoada.isOverdue(depois)).isFalse();
            Debt estornada = umaDividaComOPedro();
            estornada.voidRecord("x", HOJE);
            assertThat(estornada.isOverdue(depois)).isFalse();
            Debt semData = Debt.withoutMovement(UUID.randomUUID(), USER_ID, umJoao(), DebtDirection.PAYABLE, Money.of("1.00"), DATE, null, null);
            assertThat(semData.isOverdue(depois)).isFalse();
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(DebtStatus.class)
    @DisplayName("CA10: matriz operações × status de Debt")
    void deveRespeitarAMatriz(DebtStatus status) {
        boolean encerrada = status == DebtStatus.CANCELED || status == DebtStatus.VOIDED;
        boolean comQuitacao = status == DebtStatus.PARTIALLY_SETTLED || status == DebtStatus.SETTLED;

        Debt d1 = umaDividaNoStatus(status);
        if (encerrada || status == DebtStatus.SETTLED) {
            assertThatThrownBy(() -> d1.registerSettlement(SECOND_TX, Money.of("0.01"))).isInstanceOf(DomainException.class);
        } else {
            d1.registerSettlement(SECOND_TX, Money.of("0.01"));
        }
        Debt d2 = umaDividaNoStatus(status);
        expect(status == DebtStatus.OPEN || status == DebtStatus.PARTIALLY_SETTLED, d -> d.cancel("x", HOJE), d2,
                encerrada ? "DEBT_CLOSED" : "DEBT_ALREADY_SETTLED");
        assertThat(d2.status()).isEqualTo(status == DebtStatus.OPEN || status == DebtStatus.PARTIALLY_SETTLED ? DebtStatus.CANCELED : status);
        Debt d3 = umaDividaNoStatus(status);
        expect(status == DebtStatus.OPEN, d -> d.voidRecord("x", HOJE), d3, encerrada ? "DEBT_CLOSED" : "DEBT_HAS_SETTLEMENTS");
        Debt d4 = umaDividaNoStatus(status);
        if (comQuitacao) {
            d4.voidSettlement(SETTLEMENT_TX_ID);
            assertThat(d4.status()).isEqualTo(DebtStatus.OPEN);
        } else if (status == DebtStatus.CANCELED) {
            assertThatThrownBy(() -> d4.voidSettlement(SETTLEMENT_TX_ID)).extracting(e -> ((DomainException) e).code()).isEqualTo("DEBT_CLOSED");
        } else {
            assertThatThrownBy(() -> d4.voidSettlement(SETTLEMENT_TX_ID)).isInstanceOf(DebtNotFoundException.class);
        }
    }

    private static void expect(boolean allowed, Consumer<Debt> op, Debt debt, String code) {
        if (allowed) {
            op.accept(debt);
        } else {
            assertThatThrownBy(() -> op.accept(debt)).extracting(e -> ((DomainException) e).code()).isEqualTo(code);
        }
    }

    private static Debt umaDividaNoStatus(DebtStatus status) {
        Debt debt = umaDividaComOPedro();
        switch (status) {
            case OPEN -> { }
            case PARTIALLY_SETTLED -> debt.registerSettlement(SETTLEMENT_TX_ID, Money.of("50.00"));
            case SETTLED -> debt.registerSettlement(SETTLEMENT_TX_ID, Money.of("200.00"));
            case CANCELED -> debt.cancel("perdão", HOJE);
            case VOIDED -> debt.voidRecord("erro", HOJE);
        }
        assertThat(debt.status()).isEqualTo(status);
        return debt;
    }

    @Test
    @DisplayName("invariantes da dívida e da pessoa")
    void deveValidarInvariantes() {
        assertThatThrownBy(() -> Debt.withoutMovement(UUID.randomUUID(), USER_ID, umJoao(), DebtDirection.PAYABLE, Money.of("0.00"), DATE, null, null))
                .isInstanceOf(InvalidDebtPrincipalException.class);
        assertThatThrownBy(() -> Debt.withoutMovement(UUID.randomUUID(), USER_ID, null, DebtDirection.PAYABLE, Money.of("1.00"), DATE, null, null))
                .isInstanceOf(DebtFieldRequiredException.class);
        assertThatThrownBy(() -> Debt.withMovement(UUID.randomUUID(), USER_ID, umJoao(), DebtDirection.PAYABLE, Money.of("1.00"), DATE, null, null, null))
                .isInstanceOf(DebtFieldRequiredException.class);
        Debt debt = umaDividaComOPedro();
        assertThatThrownBy(() -> debt.cancel(" ", HOJE)).isInstanceOf(DebtReasonRequiredException.class);
        assertThatThrownBy(() -> debt.cancel("x".repeat(501), HOJE)).extracting(e -> ((DomainException) e).code()).isEqualTo("DEBT_REASON_TOO_LONG");
        assertThatThrownBy(() -> debt.ensureBelongsTo(OTHER_USER_ID)).isInstanceOf(DebtNotFoundException.class);
        assertThat(debt.personId()).isEqualTo(umPedro().id());
        assertThat(debt.userId()).isEqualTo(USER_ID);
        assertThat(debt.description()).isEqualTo("show");
        assertThat(debt.date()).isEqualTo(DATE);
        assertThat(debt).isEqualTo(umaDividaComOPedro()).hasSameHashCodeAs(umaDividaComOPedro());
        assertThat(debt.toString()).contains("PAYABLE");

        Person pessoa = umJoao();
        pessoa.rename(" Joãozinho ");
        assertThat(pessoa.name()).isEqualTo("Joãozinho");
        assertThat(pessoa.notes()).isNull();
        assertThat(umPedro().notes()).isEqualTo("colega");
        pessoa.archive();
        assertThat(pessoa.isArchived()).isTrue();
        assertThatThrownBy(pessoa::archive).isInstanceOf(PersonArchiveStateException.class);
        assertThatThrownBy(() -> Debt.withoutMovement(UUID.randomUUID(), USER_ID, pessoa, DebtDirection.PAYABLE, Money.of("1.00"), DATE, null, null))
                .isInstanceOf(PersonArchivedException.class);
        pessoa.unarchive();
        assertThatThrownBy(pessoa::unarchive).isInstanceOf(PersonArchiveStateException.class);
        assertThatThrownBy(() -> Person.create(UUID.randomUUID(), USER_ID, " ", null)).isInstanceOf(PersonNameRequiredException.class);
        assertThatThrownBy(() -> Person.create(null, USER_ID, "x", null)).isInstanceOf(DebtFieldRequiredException.class);
        assertThatThrownBy(() -> Person.create(UUID.randomUUID(), null, "x", null)).isInstanceOf(DebtFieldRequiredException.class);
        assertThat(Person.restore(pessoa.id(), USER_ID, "J", null, true).isArchived()).isTrue();
        assertThat(pessoa).isEqualTo(umJoao()).hasSameHashCodeAs(umJoao());
        assertThat(pessoa.userId()).isEqualTo(USER_ID);
    }
}
