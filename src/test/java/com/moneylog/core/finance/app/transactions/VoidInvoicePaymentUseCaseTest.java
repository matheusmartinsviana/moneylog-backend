package com.moneylog.core.finance.app.transactions;

import static com.moneylog.core.util.Ids.USER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import com.moneylog.core.account.app.gateways.AccountGateway;
import com.moneylog.core.account.domain.Account;
import com.moneylog.core.account.domain.AccountStatus;
import com.moneylog.core.account.exception.AccountExceptions.AccountArchivedException;
import com.moneylog.core.account.util.factory.AccountTestFactory;
import com.moneylog.core.card.app.gateways.CardGateway;
import com.moneylog.core.card.domain.InvoiceStatus;
import com.moneylog.core.category.app.gateways.CategoryGateway;
import com.moneylog.core.finance.app.gateways.LedgerWriteGateway;
import com.moneylog.core.finance.app.transactions.usecase.VoidTransactionUseCase.VoidTransactionCommand;
import com.moneylog.core.finance.app.transactions.usecase.VoidTransactionUseCaseImpl;
import com.moneylog.core.finance.util.FinanceScenarioFactory;
import com.moneylog.core.sk.ledger.PostingSourceType;
import com.moneylog.core.sk.persistence.Persisted;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.core.transaction.app.gateways.TransactionGateway;
import com.moneylog.core.transaction.domain.Transaction;
import com.moneylog.core.transaction.domain.TransactionStatus;
import com.moneylog.core.transaction.exception.TransactionExceptions.TransactionAlreadyVoidedException;

@DisplayName("Estorno de pagamento de fatura (T55 CA6)")
class VoidInvoicePaymentUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-11-06T15:00:00Z");
    private static final LocalDate NOV_06 = LocalDate.of(2026, 11, 6);

    private final FinanceScenarioFactory scenario = FinanceScenarioFactory.umCenarioBase();
    private final TransactionGateway transactions = mock(TransactionGateway.class);
    private final AccountGateway accounts = mock(AccountGateway.class);
    private final CardGateway cards = mock(CardGateway.class);
    private final CategoryGateway categories = mock(CategoryGateway.class);
    private final LedgerWriteGateway ledger = mock(LedgerWriteGateway.class);
    private final TimeProvider time = mock(TimeProvider.class);
    private VoidTransactionUseCaseImpl useCase;
    private Transaction payment;

    @BeforeEach
    void setUp() {
        scenario.caso2CompraParcelada();
        scenario.compraAVista1083();
        payment = scenario.pagarFatura("1283.00", LocalDate.of(2026, 10, 25), LocalDate.of(2026, 11, 5));
        when(time.today()).thenReturn(NOV_06);
        when(time.now()).thenReturn(NOW);
        when(cards.findById(USER_ID, scenario.roxinho.id())).thenReturn(Optional.of(new Persisted<>(scenario.roxinho, NOW, NOW)));
        when(accounts.findById(USER_ID, scenario.nubank.id())).thenReturn(Optional.of(new Persisted<>(scenario.nubank, NOW, NOW)));
        when(transactions.findById(USER_ID, payment.id())).thenReturn(Optional.of(new Persisted<>(payment, NOW, NOW)));
        when(transactions.findAllOfCard(USER_ID, scenario.roxinho.id())).thenAnswer(call -> new ArrayList<>(scenario.transactions));
        when(transactions.update(any())).thenAnswer(call -> new Persisted<>(call.getArgument(0), NOW, NOW));
        useCase = new VoidTransactionUseCaseImpl(transactions, accounts, cards, ledger,
                new TransactionDetailsAssembler(transactions, accounts, categories, cards, time), time);
    }

    @Test
    @DisplayName("estorna o pagamento: VOIDED, lançamentos desativados, fatura volta a OVERDUE com 1.283,00 em aberto; trava movimentação → cartão → conta")
    void deveEstornarOPagamento() {
        var details = useCase.handle(new VoidTransactionCommand(USER_ID, payment.id(), "paguei na conta errada"));

        assertThat(details.transaction().status()).isEqualTo(TransactionStatus.VOIDED);
        assertThat(details.flags().voidable()).isFalse();
        assertThat(details.impact().cash().isZero()).isTrue();
        var invoice = scenario.chain().invoice(LocalDate.of(2026, 10, 25));
        assertThat(invoice.statusAt(NOV_06)).isEqualTo(InvoiceStatus.OVERDUE);
        assertThat(invoice.outstanding().toPlainString()).isEqualTo("1283.00");
        verify(ledger).deactivateBySource(eq(USER_ID), eq(PostingSourceType.TRANSACTION), eq(payment.id()), eq("VOID"), eq(NOW));
        InOrder order = inOrder(transactions, cards, accounts);
        order.verify(transactions).lockForUpdate(USER_ID, payment.id());
        order.verify(cards).lockForUpdate(USER_ID, scenario.roxinho.id());
        order.verify(accounts).lockForUpdate(USER_ID, scenario.nubank.id());
        order.verify(transactions).findAllOfCard(USER_ID, scenario.roxinho.id());
        order.verify(transactions).update(any());
    }

    @Test
    @DisplayName("já estornado: TRANSACTION_ALREADY_VOIDED; conta arquivada: ACCOUNT_ARCHIVED")
    void deveRecusar() {
        useCase.handle(new VoidTransactionCommand(USER_ID, payment.id(), "x"));
        assertThatThrownBy(() -> useCase.handle(new VoidTransactionCommand(USER_ID, payment.id(), "de novo")))
                .isInstanceOf(TransactionAlreadyVoidedException.class);

        Transaction another = scenario.pagarFatura("10.00", LocalDate.of(2026, 10, 25), NOV_06);
        Account archived = AccountTestFactory.umaContaNoStatus(AccountStatus.ARCHIVED);
        when(transactions.findById(USER_ID, another.id())).thenReturn(Optional.of(new Persisted<>(another, NOW, NOW)));
        when(accounts.findById(USER_ID, scenario.nubank.id())).thenReturn(Optional.of(new Persisted<>(archived, NOW, NOW)));
        assertThatThrownBy(() -> useCase.handle(new VoidTransactionCommand(USER_ID, another.id(), "x")))
                .isInstanceOf(AccountArchivedException.class);
        verify(ledger, never()).deactivateBySource(any(), any(), eq(another.id()), any(), any());
    }

    @Test
    @DisplayName("a flag voidable do pagamento é true com a conta ativa e false (ACCOUNT_ARCHIVED) com a conta arquivada")
    void deveExporAFlag() {
        assertThat(TransactionFlags.of(payment, List.of(scenario.nubank), false).voidable()).isTrue();
        assertThat(TransactionFlags.of(payment, List.of(scenario.nubank), false).editable()).isFalse();
        var blocked = TransactionFlags.of(payment, List.of(AccountTestFactory.umaContaArquivada()), false);
        assertThat(blocked.voidable()).isFalse();
        assertThat(blocked.voidBlockedBy()).isEqualTo(VoidBlockedBy.ACCOUNT_ARCHIVED);
    }
}
