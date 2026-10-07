package com.moneylog.core.finance.app.transactions;

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
import org.mockito.InOrder;

import com.moneylog.core.account.app.gateways.AccountGateway;
import com.moneylog.core.account.domain.Account;
import com.moneylog.core.account.util.factory.AccountTestFactory;
import com.moneylog.core.card.app.gateways.CardGateway;
import com.moneylog.core.card.util.factory.CreditCardTestFactory;
import com.moneylog.core.category.app.gateways.CategoryGateway;
import com.moneylog.core.category.domain.Category;
import com.moneylog.core.category.util.factory.CategoryTestFactory;
import com.moneylog.core.finance.app.transactions.handlers.TransactionHandlers;
import com.moneylog.core.finance.app.transactions.handlers.TransactionTypeHandler;
import com.moneylog.core.finance.app.transactions.usecase.CreateTransactionCommand;
import com.moneylog.core.finance.app.transactions.usecase.CreateTransactionCommand.CardPurchase;
import com.moneylog.core.finance.app.transactions.usecase.CreateTransactionCommand.Common;
import com.moneylog.core.finance.app.transactions.usecase.CreateTransactionCommand.Expense;
import com.moneylog.core.finance.app.transactions.usecase.CreateTransactionCommand.Refund;
import com.moneylog.core.finance.app.transactions.usecase.CreateTransactionCommand.Transfer;
import com.moneylog.core.finance.app.transactions.usecase.CreateTransactionCommand.Unavailable;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.persistence.Persisted;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.core.transaction.app.gateways.TransactionGateway;
import com.moneylog.core.transaction.domain.PaymentMethod;
import com.moneylog.core.transaction.domain.Transaction;
import com.moneylog.core.transaction.domain.TransactionOrigin;
import com.moneylog.core.transaction.domain.TransactionType;
import com.moneylog.core.transaction.exception.TransactionExceptions.TransactionTypeNotAvailableException;

/** Despacho por handlers de tipo (S-127) e a ordem global de travas: movimentações → cartão → contas por id. */
@DisplayName("Handlers por tipo e ordem de travas (S-127, ADR-006)")
class TransactionHandlersTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 10);
    private static final Instant NOW = Instant.parse("2026-10-10T15:00:00Z");
    private static final UUID TX_ID = UUID.fromString("00000000-0000-0000-0000-0000000e0001");

    private TransactionGateway transactions;
    private AccountGateway accounts;
    private CategoryGateway categories;
    private CardGateway cards;
    private TimeProvider time;
    private AccountTransactionFactory factory;
    private final Account nubank = AccountTestFactory.umaContaCorrente();
    private final Account xp = AccountTestFactory.umaContaSemSaldo();
    private final Category food = CategoryTestFactory.umaCategoriaDeDespesa();

    @BeforeEach
    void setUp() {
        transactions = mock(TransactionGateway.class);
        accounts = mock(AccountGateway.class);
        categories = mock(CategoryGateway.class);
        cards = mock(CardGateway.class);
        time = mock(TimeProvider.class);
        when(time.today()).thenReturn(TODAY);
        for (Account account : List.of(nubank, xp)) {
            when(accounts.findById(USER_ID, account.id())).thenReturn(Optional.of(new Persisted<>(account, NOW, NOW)));
        }
        when(categories.findById(USER_ID, food.id())).thenReturn(Optional.of(food));
        when(cards.findById(USER_ID, CreditCardTestFactory.ROXINHO_ID))
                .thenReturn(Optional.of(new Persisted<>(CreditCardTestFactory.umCartao(), NOW, NOW)));
        when(transactions.findAllOfCard(any(), any())).thenReturn(List.of());
        factory = new AccountTransactionFactory(transactions, accounts, categories, cards, time);
    }

    private static Common common() {
        return new Common(USER_ID, Money.of("10.00"), TODAY, "Teste", null);
    }

    @Test
    @DisplayName("transferência trava as duas contas em ordem de id, mesmo se vierem invertidas, antes de ler qualquer coisa")
    void deveTravarContasEmOrdemDeId() {
        factory.build(new Transfer(common(), xp.id(), nubank.id()), TX_ID, TransactionOrigin.manual(), null);

        InOrder order = inOrder(accounts);
        order.verify(accounts).lockForUpdate(USER_ID, nubank.id());
        order.verify(accounts).lockForUpdate(USER_ID, xp.id());
        order.verify(accounts).findById(USER_ID, xp.id());
    }

    @Test
    @DisplayName("reembolso ligado: trava a despesa original (movimentação) antes das contas")
    void deveTravarMovimentacaoAntesDasContas() {
        UUID original = UUID.fromString("00000000-0000-0000-0000-0000000e00aa");
        Expense expense = new Expense(common(), nubank.id(), food.id(), PaymentMethod.PIX);
        Transaction originalExpense = factory.build(expense, original, TransactionOrigin.manual(), null);
        when(transactions.findById(USER_ID, original)).thenReturn(Optional.of(new Persisted<>(originalExpense, NOW, NOW)));
        when(transactions.sumActiveRefundsOf(USER_ID, original)).thenReturn(Money.of("0.00"));

        factory.build(new Refund(common(), nubank.id(), food.id(), original, null), TX_ID, TransactionOrigin.manual(), null);

        InOrder order = inOrder(transactions, accounts);
        order.verify(transactions).lockForUpdate(USER_ID, original);
        order.verify(accounts).lockForUpdate(USER_ID, nubank.id());
    }

    @Test
    @DisplayName("compra no cartão trava o cartão e nenhuma conta; ao editar uma movimentação de cartão, o cartão dela também é travado")
    void deveTravarOCartao() {
        factory.build(new CardPurchase(common(), CreditCardTestFactory.ROXINHO_ID, food.id(), 1), TX_ID, TransactionOrigin.manual(), null);

        verify(cards).lockForUpdate(USER_ID, CreditCardTestFactory.ROXINHO_ID);
        verify(accounts, never()).lockForUpdate(any(), any());
    }

    @Test
    @DisplayName("a prévia (buildUnlocked) monta a compra sem travar cartão nem conta")
    void naoDeveTravarNaPrevia() {
        Transaction purchase = factory.buildUnlocked(new CardPurchase(common(), CreditCardTestFactory.ROXINHO_ID, food.id(), 3), TX_ID,
                TransactionOrigin.manual(), null);

        assertThat(purchase.type()).isEqualTo(TransactionType.CARD_PURCHASE);
        assertThat(purchase.installments()).hasSize(3);
        verify(cards, never()).lockForUpdate(any(), any());
        verify(accounts, never()).lockForUpdate(any(), any());
        verify(transactions, never()).lockForUpdate(any(), any());
    }

    @Test
    @DisplayName("os tipos sem fluxo respondem TRANSACTION_TYPE_NOT_AVAILABLE, e o reembolso no cartão (cardId) também sem a ligação de cartão (T56), sem tentar travar conta nula")
    void deveSerIndisponivel() {
        AccountTransactionFactory accountsOnly = new AccountTransactionFactory(transactions, accounts, categories);
        assertThatThrownBy(() -> accountsOnly.build(new Refund(common(), null, food.id(), null, UUID.randomUUID()), TX_ID,
                TransactionOrigin.manual(), null)).isInstanceOf(TransactionTypeNotAvailableException.class);
        assertThatThrownBy(() -> factory.build(new Unavailable(USER_ID, TransactionType.LOAN_GIVEN), TX_ID, TransactionOrigin.manual(), null))
                .isInstanceOf(TransactionTypeNotAvailableException.class);
        verify(accounts, never()).lockForUpdate(any(), any());
    }

    @Test
    @DisplayName("o registro recusa dois handlers do mesmo comando e comando sem handler é TRANSACTION_TYPE_NOT_AVAILABLE")
    void deveValidarORegistro() {
        TransactionTypeHandler<Unavailable> handler = new TransactionTypeHandler<>() {
            @Override
            public Class<Unavailable> commandType() {
                return Unavailable.class;
            }

            @Override
            public Transaction build(Unavailable command, UUID id, TransactionOrigin origin, Transaction editing) {
                throw new UnsupportedOperationException();
            }
        };

        assertThatThrownBy(() -> new TransactionHandlers(List.of(handler, handler))).isInstanceOf(IllegalArgumentException.class);
        TransactionHandlers empty = new TransactionHandlers(List.of());
        CreateTransactionCommand command = new CardPurchase(common(), CreditCardTestFactory.ROXINHO_ID, food.id(), 1);
        assertThatThrownBy(() -> empty.forCommand(command)).isInstanceOf(TransactionTypeNotAvailableException.class);
        assertThat(new TransactionHandlers(List.of(handler)).forCommand(new Unavailable(USER_ID, TransactionType.DEBT_PAYMENT)))
                .isSameAs(handler);
    }
}
