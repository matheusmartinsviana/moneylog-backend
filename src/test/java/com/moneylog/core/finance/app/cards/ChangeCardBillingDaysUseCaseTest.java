package com.moneylog.core.finance.app.cards;

import static com.moneylog.core.util.Ids.USER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import com.moneylog.core.account.app.gateways.AccountGateway;
import com.moneylog.core.account.util.factory.AccountTestFactory;
import com.moneylog.core.card.app.gateways.CardGateway;
import com.moneylog.core.card.domain.CreditCard;
import com.moneylog.core.card.exception.CardExceptions.BillingChangeNotConfirmedException;
import com.moneylog.core.card.exception.CardExceptions.CardBillingChangeStaleException;
import com.moneylog.core.card.exception.CardExceptions.CardNotFoundException;
import com.moneylog.core.card.exception.CardExceptions.InvalidBillingDayException;
import com.moneylog.core.finance.app.cards.usecase.ChangeCardBillingDaysUseCase.ChangeCardBillingDaysCommand;
import com.moneylog.core.finance.app.cards.usecase.ChangeCardBillingDaysUseCaseImpl;
import com.moneylog.core.finance.app.cards.usecase.PreviewBillingChangeUseCase.PreviewBillingChangeQuery;
import com.moneylog.core.finance.app.cards.usecase.PreviewBillingChangeUseCaseImpl;
import com.moneylog.core.finance.app.gateways.FinancialRecordsGateway;
import com.moneylog.core.finance.util.FinanceScenarioFactory;
import com.moneylog.core.sk.exception.VersionConflictException;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.persistence.Persisted;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.core.transaction.app.gateways.TransactionGateway;
import com.moneylog.core.transaction.domain.Transaction;
import com.moneylog.core.transaction.util.factory.TransactionTestFactory;

@DisplayName("Mudança de fechamento/vencimento e prévia (T58)")
class ChangeCardBillingDaysUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-10-10T15:00:00Z");
    private static final LocalDate OCT_10 = LocalDate.of(2026, 10, 10);
    private static final LocalDate NOV_25 = LocalDate.of(2026, 11, 25);

    private final FinanceScenarioFactory scenario = FinanceScenarioFactory.umCenarioBase();
    private final CardGateway cards = mock(CardGateway.class);
    private final AccountGateway accounts = mock(AccountGateway.class);
    private final TransactionGateway transactions = mock(TransactionGateway.class);
    private final FinancialRecordsGateway records = mock(FinancialRecordsGateway.class);
    private final TimeProvider time = mock(TimeProvider.class);
    private final UUID cardId = scenario.roxinho.id();
    private ChangeCardBillingDaysUseCaseImpl change;
    private PreviewBillingChangeUseCaseImpl preview;
    private Transaction notebook;

    @BeforeEach
    void setUp() {
        notebook = scenario.caso2CompraParcelada();
        when(time.today()).thenReturn(OCT_10);
        when(cards.findById(USER_ID, cardId)).thenAnswer(call -> Optional.of(new Persisted<>(scenario.roxinho, NOW, NOW)));
        when(cards.save(any())).thenAnswer(call -> new Persisted<>(call.getArgument(0), NOW, NOW));
        when(transactions.findAllOfCard(USER_ID, cardId)).thenAnswer(call -> List.copyOf(scenario.transactions));
        when(transactions.update(any())).thenAnswer(call -> new Persisted<>(call.getArgument(0), NOW, NOW));
        when(records.load(USER_ID)).thenAnswer(call -> scenario.records());
        when(accounts.findById(USER_ID, AccountTestFactory.NUBANK_ID))
                .thenReturn(Optional.of(new Persisted<>(scenario.nubank, NOW, NOW)));
        change = new ChangeCardBillingDaysUseCaseImpl(cards, accounts, transactions, records, time);
        preview = new PreviewBillingChangeUseCaseImpl(records, time);
    }

    private ChangeCardBillingDaysCommand command(int closing, int due, LocalDate confirmed, long version) {
        return new ChangeCardBillingDaysCommand(USER_ID, cardId, "Roxinho", "Nubank", Money.of("10000.00"), closing, due,
                AccountTestFactory.NUBANK_ID, confirmed, version);
    }

    @Test
    @DisplayName("CA1: fechamento 25 e vencimento 5 → 28: a corrente continua vencendo 05/11; a seguinte fecha 25/11 e vence 28/11; as parcelas ficam nos mesmos ciclos e nada muda de fatura")
    void deveMudarSoOVencimento() {
        change.handle(command(25, 28, NOV_25, 0));

        CreditCard saved = scenario.roxinho;
        assertThat(saved.dueDay()).isEqualTo(28);
        assertThat(saved.billingRules()).hasSize(2);
        assertThat(saved.calendar().periodClosingIn(java.time.YearMonth.of(2026, 10)).dueDate()).isEqualTo(LocalDate.of(2026, 11, 5));
        var next = saved.calendar().periodClosingIn(java.time.YearMonth.of(2026, 11));
        assertThat(next.closingDate()).isEqualTo(NOV_25);
        assertThat(next.dueDate()).isEqualTo(LocalDate.of(2026, 11, 28));
        assertThat(next.referenceMonth().toString()).isEqualTo("2026-11");
        assertThat(notebook.installments().stream().map(i -> i.invoiceClosingDate().toString()).toList())
                .containsExactly("2026-10-25", "2026-11-25", "2026-12-25", "2027-01-25", "2027-02-25", "2027-03-25");
        verify(transactions, never()).update(any());
        verify(cards).refreshInvoiceRows(USER_ID, saved);
    }

    @Test
    @DisplayName("CA2: fechamento 25 → 10 com a corrente fechando 25/10: a primeira fatura nova fecha 10/11; as parcelas 2..6 vão para 10/11..10/03; as faturas até 25/10 não mudam")
    void deveMudarOFechamento() {
        change.handle(command(10, 5, NOV_25, 0));

        assertThat(scenario.roxinho.closingDay()).isEqualTo(10);
        assertThat(notebook.installments().stream().map(i -> i.invoiceClosingDate().toString()).toList())
                .containsExactly("2026-10-25", "2026-11-10", "2026-12-10", "2027-01-10", "2027-02-10", "2027-03-10");
        ArgumentCaptor<Transaction> moved = ArgumentCaptor.forClass(Transaction.class);
        verify(transactions).update(moved.capture());
        assertThat(moved.getValue().id()).isEqualTo(notebook.id());
        assertThat(scenario.chain().invoices()).hasSize(6);
        assertThat(scenario.chain().invoice(LocalDate.of(2026, 10, 25)).total()).isEqualTo(Money.of("200.00"));
    }

    @Test
    @DisplayName("grava na ordem: trava as movimentações do cartão → o cartão (e relê depois), cartão e regras → movimentações → linhas de fatura (datas e poda); devolve o cartão com os números calculados")
    void deveGravarNaOrdemCerta() {
        CardDetails details = change.handle(command(10, 5, NOV_25, 0));

        InOrder order = inOrder(transactions, cards);
        order.verify(transactions).lockAllOfCard(USER_ID, cardId);
        order.verify(cards).lockForUpdate(USER_ID, cardId);
        order.verify(cards).findById(USER_ID, cardId);
        order.verify(transactions).findAllOfCard(USER_ID, cardId);
        order.verify(cards).save(scenario.roxinho);
        order.verify(transactions).update(notebook);
        order.verify(cards).refreshInvoiceRows(USER_ID, scenario.roxinho);
        assertThat(details.card().closingDay()).isEqualTo(10);
    }

    @Test
    @DisplayName("CA4: confirmedEffectiveFrom que não é o da prévia atual: CARD_BILLING_CHANGE_STALE; ausente: CARD_BILLING_CHANGE_NOT_CONFIRMED; nada é gravado")
    void deveExigirAConfirmacaoDaPrevia() {
        assertThatThrownBy(() -> change.handle(command(10, 5, LocalDate.of(2026, 10, 25), 0))).isInstanceOf(CardBillingChangeStaleException.class)
                .hasFieldOrPropertyWithValue("code", "CARD_BILLING_CHANGE_STALE");
        assertThatThrownBy(() -> change.handle(command(10, 5, null, 0))).isInstanceOf(BillingChangeNotConfirmedException.class)
                .hasFieldOrPropertyWithValue("code", "CARD_BILLING_CHANGE_NOT_CONFIRMED");
        when(time.today()).thenReturn(LocalDate.of(2026, 10, 30));
        assertThatThrownBy(() -> change.handle(command(10, 5, NOV_25, 0))).isInstanceOf(CardBillingChangeStaleException.class);
        change.handle(command(10, 5, LocalDate.of(2026, 12, 25), 0));
    }

    @Test
    @DisplayName("versão desatualizada: VERSION_CONFLICT; dia fora de 1..31: CARD_INVALID_BILLING_DAY (antes de pedir a confirmação); cartão inexistente: CARD_NOT_FOUND")
    void deveRecusarVersaoDiaEInexistente() {
        assertThatThrownBy(() -> change.handle(command(10, 5, NOV_25, 7))).isInstanceOf(VersionConflictException.class);
        assertThatThrownBy(() -> change.handle(command(32, 5, null, 0))).isInstanceOf(InvalidBillingDayException.class)
                .hasFieldOrPropertyWithValue("code", "CARD_INVALID_BILLING_DAY");
        assertThatThrownBy(() -> change.handle(command(10, 0, NOV_25, 0))).isInstanceOf(InvalidBillingDayException.class);
        when(cards.findById(USER_ID, cardId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> change.handle(command(10, 5, NOV_25, 0))).isInstanceOf(CardNotFoundException.class);
        verify(cards, never()).save(any());
        verify(transactions, never()).update(any());
        verify(cards, never()).refreshInvoiceRows(any(), any());
    }

    @Test
    @DisplayName("os detalhes (nome, limite) mudam junto com os dias, na mesma gravação do cartão")
    void deveAplicarDetalhesEDias() {
        change.handle(new ChangeCardBillingDaysCommand(USER_ID, cardId, "Roxinho Ultra", "Nu", Money.of("12000.00"), 10, 5,
                AccountTestFactory.NUBANK_ID, NOV_25, 0));

        assertThat(scenario.roxinho.name()).isEqualTo("Roxinho Ultra");
        assertThat(scenario.roxinho.limit()).isEqualTo(Money.of("12000.00"));
        assertThat(scenario.roxinho.closingDay()).isEqualTo(10);
        verify(cards).save(scenario.roxinho);
    }

    @Test
    @DisplayName("CA3: a prévia devolve a primeira fatura afetada (25/11 hoje; nova 10/11, vence 05/12), 5 parcelas movidas, 0 créditos, e não muda cartão nem movimentações")
    void devePrevisualizarSemMudarNada() {
        var view = preview.handle(new PreviewBillingChangeQuery(USER_ID, cardId, 10, 5));

        assertThat(view.effectiveFromClosingDate()).isEqualTo(NOV_25);
        assertThat(view.firstNewClosingDate()).isEqualTo(LocalDate.of(2026, 11, 10));
        assertThat(view.firstNewDueDate()).isEqualTo(LocalDate.of(2026, 12, 5));
        assertThat(view.movedInstallments()).isEqualTo(5);
        assertThat(view.movedCredits()).isZero();
        assertThat(scenario.roxinho.closingDay()).isEqualTo(25);
        assertThat(scenario.roxinho.billingRules()).hasSize(1);
        assertThat(notebook.installments().stream().map(i -> i.invoiceClosingDate().toString()).toList())
                .containsExactly("2026-10-25", "2026-11-25", "2026-12-25", "2027-01-25", "2027-02-25", "2027-03-25");
        verify(transactions, never()).update(any());
        verify(cards, never()).save(any());
    }

    @Test
    @DisplayName("a prévia conta créditos e ignora parcelas canceladas e compras estornadas; os mesmos dias não movem nada")
    void deveContarCreditosEIgnorarInativos() {
        Transaction credit = com.moneylog.core.transaction.domain.CardTransactions.refundToCard(scenario.header(LocalDate.of(2026, 11, 11), "Crédito"),
                Money.of("30.00"), scenario.roxinho, scenario.alimentacao, null, scenario.chain(), OCT_10);
        scenario.transactions.add(credit);
        Transaction voided = scenario.compraNoCartao("600.00", 3);
        scenario.estornar(voided, "erro", OCT_10);

        var view = preview.handle(new PreviewBillingChangeQuery(USER_ID, cardId, 10, 5));

        assertThat(view.movedInstallments()).isEqualTo(5);
        assertThat(view.movedCredits()).isEqualTo(1);
        var same = preview.handle(new PreviewBillingChangeQuery(USER_ID, cardId, 25, 5));
        assertThat(same.movedInstallments()).isZero();
        assertThat(same.movedCredits()).isZero();
        assertThatThrownBy(() -> preview.handle(new PreviewBillingChangeQuery(USER_ID, cardId, 0, 5))).isInstanceOf(InvalidBillingDayException.class);
        assertThatThrownBy(() -> preview.handle(new PreviewBillingChangeQuery(USER_ID, UUID.randomUUID(), 10, 5)))
                .isInstanceOf(CardNotFoundException.class);
        assertThat(TransactionTestFactory.DATE).isNotNull();
    }
}
