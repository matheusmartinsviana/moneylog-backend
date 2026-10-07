package com.moneylog.core.finance.app.transactions;

import static com.moneylog.core.util.Ids.USER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.moneylog.core.account.app.gateways.AccountGateway;
import com.moneylog.core.account.domain.Account;
import com.moneylog.core.account.util.factory.AccountTestFactory;
import com.moneylog.core.category.app.SystemCategories;
import com.moneylog.core.category.app.gateways.CategoryGateway;
import com.moneylog.core.category.domain.Category;
import com.moneylog.core.category.domain.CategoryKind;
import com.moneylog.core.category.exception.CategoryExceptions.DefaultCategoryUnavailableException;
import com.moneylog.core.category.util.factory.CategoryTestFactory;
import com.moneylog.core.finance.app.gateways.LedgerWriteGateway;
import com.moneylog.core.finance.app.transactions.usecase.CreateTransactionCommand;
import com.moneylog.core.finance.app.transactions.usecase.CreateTransactionCommand.Common;
import com.moneylog.core.finance.app.transactions.usecase.CreateTransactionUseCaseImpl;
import com.moneylog.core.sk.id.IdGenerator;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.persistence.Persisted;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.core.transaction.app.gateways.IdempotencyGateway;
import com.moneylog.core.transaction.app.gateways.TransactionGateway;
import com.moneylog.core.transaction.domain.PaymentMethod;
import com.moneylog.core.transaction.domain.Transaction;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Campos opcionais resolvidos no servidor: categoria padrão, descrição ausente (PR 01 intacto)")
class OptionalFieldsDefaultsTest {

    private static final Instant NOW = Instant.parse("2026-10-10T15:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 10);
    private static final UUID TX_ID = UUID.fromString("00000000-0000-0000-0000-0000000e0001");

    @Mock
    private TransactionGateway transactions;
    @Mock
    private AccountGateway accounts;
    @Mock
    private CategoryGateway categories;
    @Mock
    private LedgerWriteGateway ledger;
    @Mock
    private IdGenerator ids;
    @Mock
    private IdempotencyGateway idempotency;
    @Mock
    private TimeProvider time;

    private final Account nubank = AccountTestFactory.umaContaCorrente();
    private final Category food = CategoryTestFactory.umaCategoriaDeDespesa();
    private final Category otherExpense = Category.createSystem(UUID.fromString("00000000-0000-0000-0000-00000000c0a1"), USER_ID, "Outros",
            CategoryKind.EXPENSE);
    private final Category otherIncome = Category.createSystem(UUID.fromString("00000000-0000-0000-0000-00000000c0a2"), USER_ID,
            "Outras receitas", CategoryKind.INCOME);

    private CreateTransactionUseCaseImpl useCase;

    @BeforeEach
    void setUp() {
        when(accounts.findById(USER_ID, nubank.id())).thenReturn(Optional.of(new Persisted<>(nubank, NOW, NOW)));
        for (Category c : List.of(food, otherExpense, otherIncome)) {
            when(categories.findById(USER_ID, c.id())).thenReturn(Optional.of(c));
        }
        when(categories.findBySystemKey(USER_ID, SystemCategories.DEFAULT_EXPENSE_KEY)).thenReturn(Optional.of(otherExpense));
        when(categories.findBySystemKey(USER_ID, SystemCategories.DEFAULT_INCOME_KEY)).thenReturn(Optional.of(otherIncome));
        when(ids.next()).thenReturn(TX_ID);
        when(transactions.insert(any())).thenAnswer(call -> new Persisted<>(call.getArgument(0), NOW, NOW));
        when(transactions.sumActiveRefundsOf(any(), any())).thenReturn(Money.of("0.00"));
        useCase = new CreateTransactionUseCaseImpl(transactions, new AccountTransactionFactory(transactions, accounts, categories), ledger,
                ids, new TransactionDetailsAssembler(transactions, accounts, categories), idempotency, time);
    }

    private static Common common(String description) {
        return new Common(USER_ID, Money.of("100.00"), TODAY, description, null);
    }

    private static long sum(Transaction t) {
        return t.entries().stream().mapToLong(e -> e.posting().amount().cents()).sum();
    }

    @Test
    @DisplayName("despesa sem categoria e sem descrição cai em OTHER_EXPENSE; contabilmente igual à explícita (soma zero, caixa e consumo)")
    void despesaSemCategoria() {
        TransactionDetails omitted = useCase.handle(new CreateTransactionCommand.Expense(common(null), nubank.id(), null, PaymentMethod.OTHER));
        TransactionDetails explicit = useCase.handle(
                new CreateTransactionCommand.Expense(common("x"), nubank.id(), otherExpense.id(), PaymentMethod.OTHER));

        assertThat(omitted.transaction().categoryId()).isEqualTo(otherExpense.id());
        assertThat(omitted.transaction().description()).isNull();
        assertThat(sum(omitted.transaction())).isZero();
        assertThat(omitted.impact()).isEqualTo(explicit.impact());
        assertThat(omitted.impact().cash()).isEqualTo(Money.of("-100.00"));
        assertThat(omitted.impact().consumption()).singleElement().satisfies(c -> assertThat(c.amount()).isEqualTo(Money.of("100.00")));
    }

    @Test
    @DisplayName("receita sem categoria cai em OTHER_INCOME e continua sendo receita")
    void receitaSemCategoria() {
        TransactionDetails omitted = useCase.handle(new CreateTransactionCommand.Income(common(null), nubank.id(), null, PaymentMethod.OTHER));

        assertThat(omitted.transaction().categoryId()).isEqualTo(otherIncome.id());
        assertThat(sum(omitted.transaction())).isZero();
        assertThat(omitted.impact().cash()).isEqualTo(Money.of("100.00"));
        assertThat(omitted.impact().netWorth()).isEqualTo(Money.of("100.00"));
    }

    @Test
    @DisplayName("reembolso sem origem e sem categoria cai em OTHER_EXPENSE (consumo negativo)")
    void reembolsoSemOrigem() {
        TransactionDetails refund = useCase.handle(new CreateTransactionCommand.Refund(common(null), nubank.id(), null, null, null));

        assertThat(refund.transaction().categoryId()).isEqualTo(otherExpense.id());
        assertThat(refund.impact().consumption()).singleElement().satisfies(c -> assertThat(c.amount()).isEqualTo(Money.of("-100.00")));
        assertThat(sum(refund.transaction())).isZero();
    }

    @Test
    @DisplayName("reembolso com origem e sem categoria herda a da original (não vai para Outros)")
    void reembolsoComOrigemHerdaCategoria() {
        Transaction expense = useCase.handle(new CreateTransactionCommand.Expense(common("mercado"), nubank.id(), food.id(), PaymentMethod.PIX))
                .transaction();
        when(transactions.findById(USER_ID, expense.id())).thenReturn(Optional.of(new Persisted<>(expense, NOW, NOW)));
        when(ids.next()).thenReturn(UUID.fromString("00000000-0000-0000-0000-0000000e0002"));

        TransactionDetails refund = useCase.handle(
                new CreateTransactionCommand.Refund(new Common(USER_ID, Money.of("40.00"), TODAY, null, null), nubank.id(), null, expense.id(), null));

        assertThat(refund.transaction().categoryId()).isEqualTo(food.id());
        assertThat(refund.transaction().refundOfId()).isEqualTo(expense.id());
        assertThat(refund.impact().consumption()).singleElement().satisfies(c -> assertThat(c.amount()).isEqualTo(Money.of("-40.00")));
    }

    @Test
    @DisplayName("categoria padrão inexistente ou arquivada: 422 DEFAULT_CATEGORY_UNAVAILABLE, nunca 500")
    void categoriaPadraoIndisponivel() {
        when(categories.findBySystemKey(USER_ID, SystemCategories.DEFAULT_EXPENSE_KEY)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> useCase.handle(new CreateTransactionCommand.Expense(common(null), nubank.id(), null, PaymentMethod.OTHER)))
                .isInstanceOf(DefaultCategoryUnavailableException.class).hasFieldOrPropertyWithValue("code", "DEFAULT_CATEGORY_UNAVAILABLE");

        Category archived = Category.restore(otherIncome.id(), USER_ID, "Outras receitas", CategoryKind.INCOME, true, true, 1L);
        when(categories.findBySystemKey(USER_ID, SystemCategories.DEFAULT_INCOME_KEY)).thenReturn(Optional.of(archived));
        assertThatThrownBy(() -> useCase.handle(new CreateTransactionCommand.Income(common(null), nubank.id(), null, PaymentMethod.OTHER)))
                .isInstanceOf(DefaultCategoryUnavailableException.class);
    }
}
