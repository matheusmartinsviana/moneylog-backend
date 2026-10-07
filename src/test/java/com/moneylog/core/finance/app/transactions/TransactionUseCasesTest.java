package com.moneylog.core.finance.app.transactions;

import static com.moneylog.core.util.Ids.USER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.moneylog.core.account.app.gateways.AccountGateway;
import com.moneylog.core.account.domain.Account;
import com.moneylog.core.account.domain.AccountType;
import com.moneylog.core.account.exception.AccountExceptions.AccountNotFoundException;
import com.moneylog.core.account.util.factory.AccountTestFactory;
import com.moneylog.core.category.app.gateways.CategoryGateway;
import com.moneylog.core.category.domain.Category;
import com.moneylog.core.category.exception.CategoryExceptions.CategoryNotFoundException;
import com.moneylog.core.category.util.factory.CategoryTestFactory;
import com.moneylog.core.finance.app.gateways.LedgerWriteGateway;
import com.moneylog.core.finance.app.transactions.usecase.CreateTransactionCommand;
import com.moneylog.core.finance.app.transactions.usecase.CreateTransactionCommand.Common;
import com.moneylog.core.finance.app.transactions.usecase.CreateTransactionUseCaseImpl;
import com.moneylog.core.finance.app.transactions.usecase.GetTransactionUseCase.GetTransactionQuery;
import com.moneylog.core.finance.app.transactions.usecase.GetTransactionUseCaseImpl;
import com.moneylog.core.sk.id.IdGenerator;
import com.moneylog.core.sk.ledger.LedgerEntry;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.persistence.Persisted;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.core.transaction.app.gateways.IdempotencyGateway;
import com.moneylog.core.transaction.app.gateways.TransactionGateway;
import com.moneylog.core.transaction.domain.AdjustmentDirection;
import com.moneylog.core.transaction.domain.AdjustmentKind;
import com.moneylog.core.transaction.domain.InvestmentDirection;
import com.moneylog.core.transaction.domain.PaymentMethod;
import com.moneylog.core.transaction.domain.Transaction;
import com.moneylog.core.transaction.domain.TransactionType;
import com.moneylog.core.transaction.domain.TransactionVoiding;
import com.moneylog.core.transaction.domain.VoidContext;
import com.moneylog.core.transaction.exception.TransactionExceptions.RefundExceedsOriginalException;
import com.moneylog.core.transaction.exception.TransactionExceptions.SameAccountTransferException;
import com.moneylog.core.transaction.exception.TransactionExceptions.TransactionNotFoundException;
import com.moneylog.core.transaction.exception.TransactionExceptions.TransactionTypeNotAvailableException;
import com.moneylog.core.transaction.exception.TransactionExceptions.TransferAcrossLiquidityException;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Casos de uso de movimentação (T26a)")
class TransactionUseCasesTest {

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
    private final Account xp = AccountTestFactory.umaContaSemSaldo();
    private final Account xpInvest = AccountTestFactory.umaContaDeInvestimento();
    private final Category food = CategoryTestFactory.umaCategoriaDeDespesa();
    private final Category salary = CategoryTestFactory.umaCategoriaDeReceita();

    private CreateTransactionUseCaseImpl useCase;

    @BeforeEach
    void setUp() {
        for (Account account : List.of(nubank, xp, xpInvest)) {
            when(accounts.findById(USER_ID, account.id())).thenReturn(Optional.of(new Persisted<>(account, NOW, NOW)));
        }
        when(categories.findById(USER_ID, food.id())).thenReturn(Optional.of(food));
        when(categories.findById(USER_ID, salary.id())).thenReturn(Optional.of(salary));
        when(ids.next()).thenReturn(TX_ID);
        when(transactions.insert(any())).thenAnswer(call -> new Persisted<>(call.getArgument(0), NOW, NOW));
        when(transactions.sumActiveRefundsOf(any(), any())).thenReturn(Money.of("0.00"));
        useCase = new CreateTransactionUseCaseImpl(transactions, new AccountTransactionFactory(transactions, accounts, categories),
                ledger, ids, new TransactionDetailsAssembler(transactions, accounts, categories), idempotency, time);
    }

    private Common common(String amount) {
        return new Common(USER_ID, Money.of(amount), TODAY, "Movimentação", null);
    }

    private TransactionDetails create(CreateTransactionCommand command) {
        return useCase.handle(command);
    }

    @Nested
    @DisplayName("tipos em conta (S-58)")
    class TiposEmConta {

        @Test
        @DisplayName("despesa: grava a movimentação e depois os lançamentos balanceados; impacto do caso 1 do PR 01")
        void deveCriarDespesa() {
            TransactionDetails details = create(new CreateTransactionCommand.Expense(common("100.00"), nubank.id(), food.id(), PaymentMethod.PIX));

            assertThat(details.impact().cash()).isEqualTo(Money.of("-100.00"));
            assertThat(details.impact().netWorth()).isEqualTo(Money.of("-100.00"));
            assertThat(details.impact().commitment()).isEqualTo(Money.of("0.00"));
            assertThat(details.impact().consumption()).singleElement().satisfies(c -> {
                assertThat(c.competence().toString()).isEqualTo("2026-10");
                assertThat(c.amount()).isEqualTo(Money.of("100.00"));
            });
            assertThat(details.flags().editable()).isTrue();
            assertThat(details.flags().voidable()).isTrue();
            assertThat(details.flags().voidBlockedBy()).isNull();
            assertThat(details.account()).isEqualTo(nubank);
            assertThat(details.category()).isEqualTo(food);
            InOrder order = inOrder(transactions, ledger);
            ArgumentCaptor<Transaction> saved = ArgumentCaptor.forClass(Transaction.class);
            order.verify(transactions).insert(saved.capture());
            ArgumentCaptor<List<LedgerEntry>> entries = ArgumentCaptor.forClass(List.class);
            order.verify(ledger).append(org.mockito.ArgumentMatchers.eq(USER_ID), entries.capture());
            assertThat(entries.getValue()).isEqualTo(saved.getValue().entries());
            assertThat(entries.getValue().stream().map(e -> e.posting().amount().cents()).reduce(0L, Long::sum)).isZero();
        }

        @Test
        @DisplayName("transferência entre contas líquidas: caixa, consumo e patrimônio zerados (caso 4)")
        void deveCriarTransferencia() {
            TransactionDetails details = create(new CreateTransactionCommand.Transfer(common("1000.00"), nubank.id(), xp.id()));

            assertThat(details.impact().cash()).isEqualTo(Money.of("0.00"));
            assertThat(details.impact().consumption()).isEmpty();
            assertThat(details.impact().netWorth()).isEqualTo(Money.of("0.00"));
            assertThat(details.counterpartAccount()).isEqualTo(xp);
        }

        @Test
        @DisplayName("aplicação em investimento: saída de caixa, sem consumo nem variação de patrimônio (caso 5); resgate inverte")
        void deveCriarInvestimento() {
            TransactionDetails contribution = create(new CreateTransactionCommand.Investment(common("10000.00"), nubank.id(), xpInvest.id(),
                    InvestmentDirection.CONTRIBUTION));
            TransactionDetails redemption = create(new CreateTransactionCommand.Investment(common("500.00"), nubank.id(), xpInvest.id(),
                    InvestmentDirection.REDEMPTION));

            assertThat(contribution.impact().cash()).isEqualTo(Money.of("-10000.00"));
            assertThat(contribution.impact().consumption()).isEmpty();
            assertThat(contribution.impact().netWorth()).isEqualTo(Money.of("0.00"));
            assertThat(redemption.impact().cash()).isEqualTo(Money.of("500.00"));
        }

        @Test
        @DisplayName("receita: entra no caixa e no patrimônio, sem consumo")
        void deveCriarReceita() {
            TransactionDetails details = create(new CreateTransactionCommand.Income(common("3000.00"), nubank.id(), salary.id(), PaymentMethod.TRANSFER));

            assertThat(details.impact().cash()).isEqualTo(Money.of("3000.00"));
            assertThat(details.impact().netWorth()).isEqualTo(Money.of("3000.00"));
            assertThat(details.impact().consumption()).isEmpty();
        }

        @Test
        @DisplayName("ajuste exige motivo; rendimento em conta de investimento não toca caixa")
        void deveCriarAjuste() {
            TransactionDetails details = create(new CreateTransactionCommand.Adjustment(common("20.00"), xpInvest.id(),
                    AdjustmentDirection.INCREASE, AdjustmentKind.YIELD, "rendimento de outubro"));

            assertThat(details.impact().cash()).isEqualTo(Money.of("0.00"));
            assertThat(details.impact().netWorth()).isEqualTo(Money.of("20.00"));
            assertThat(details.transaction().reason()).isEqualTo("rendimento de outubro");
            assertThatThrownBy(() -> create(new CreateTransactionCommand.Adjustment(common("20.00"), nubank.id(),
                    AdjustmentDirection.DECREASE, null, " "))).hasFieldOrPropertyWithValue("code", "ADJUSTMENT_REASON_REQUIRED");
        }

        @Test
        @DisplayName("reembolso ligado à despesa: consumo negativo na competência; passar do valor original é recusado (caso 9, S-9)")
        void deveCriarReembolso() {
            Transaction expense = create(new CreateTransactionCommand.Expense(common("100.00"), nubank.id(), food.id(), PaymentMethod.PIX)).transaction();
            when(transactions.findById(USER_ID, expense.id())).thenReturn(Optional.of(new Persisted<>(expense, NOW, NOW)));
            when(ids.next()).thenReturn(UUID.fromString("00000000-0000-0000-0000-0000000e0002"));

            TransactionDetails refund = create(new CreateTransactionCommand.Refund(common("50.00"), nubank.id(), food.id(), expense.id(), null));

            assertThat(refund.impact().consumption()).singleElement().satisfies(c -> assertThat(c.amount()).isEqualTo(Money.of("-50.00")));
            assertThat(refund.impact().cash()).isEqualTo(Money.of("50.00"));
            assertThat(refund.transaction().refundOfId()).isEqualTo(expense.id());
            when(transactions.sumActiveRefundsOf(USER_ID, expense.id())).thenReturn(Money.of("50.00"));
            assertThatThrownBy(() -> create(new CreateTransactionCommand.Refund(common("60.00"), nubank.id(), food.id(), expense.id(), null)))
                    .isInstanceOf(RefundExceedsOriginalException.class);
        }

        @Test
        @DisplayName("reembolso de despesa inexistente ou de outro usuário é TRANSACTION_NOT_FOUND")
        void deveRecusarOrigemInexistente() {
            UUID missing = UUID.fromString("00000000-0000-0000-0000-0000000e00ff");
            when(transactions.findById(USER_ID, missing)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> create(new CreateTransactionCommand.Refund(common("5.00"), nubank.id(), food.id(), missing, null)))
                    .isInstanceOf(TransactionNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("recusas: nada é gravado")
    class Recusas {

        @Test
        @DisplayName("cartão, fatura, dívida e reembolso no cartão: TRANSACTION_TYPE_NOT_AVAILABLE")
        void deveRecusarTiposIndisponiveis() {
            for (TransactionType type : List.of(TransactionType.CARD_PURCHASE, TransactionType.LOAN_GIVEN, TransactionType.MONEY_RECEIVED,
                    TransactionType.DEBT_PAYMENT, TransactionType.INVOICE_PAYMENT)) {
                assertThatThrownBy(() -> create(new CreateTransactionCommand.Unavailable(USER_ID, type)))
                        .isInstanceOf(TransactionTypeNotAvailableException.class).hasFieldOrPropertyWithValue("code", "TRANSACTION_TYPE_NOT_AVAILABLE");
            }
            assertThatThrownBy(() -> create(new CreateTransactionCommand.Refund(common("5.00"), nubank.id(), food.id(), null, UUID.randomUUID())))
                    .isInstanceOf(TransactionTypeNotAvailableException.class);
            verify(transactions, never()).insert(any());
            verify(ledger, never()).append(any(), any());
        }

        @Test
        @DisplayName("regras do domínio (mesma conta, liquidez diferente, conta arquivada) e referências inexistentes")
        void deveRecusarRegrasDoDominio() {
            assertThatThrownBy(() -> create(new CreateTransactionCommand.Transfer(common("1.00"), nubank.id(), nubank.id())))
                    .isInstanceOf(SameAccountTransferException.class);
            assertThatThrownBy(() -> create(new CreateTransactionCommand.Transfer(common("1.00"), nubank.id(), xpInvest.id())))
                    .isInstanceOf(TransferAcrossLiquidityException.class);
            Account archived = AccountTestFactory.umaContaArquivada();
            when(accounts.findById(USER_ID, archived.id())).thenReturn(Optional.of(new Persisted<>(archived, NOW, NOW)));
            assertThatThrownBy(() -> create(new CreateTransactionCommand.Expense(common("1.00"), archived.id(), food.id(), PaymentMethod.PIX)))
                    .hasFieldOrPropertyWithValue("code", "ACCOUNT_ARCHIVED");
            assertThatThrownBy(() -> create(new CreateTransactionCommand.Income(common("1.00"), nubank.id(), food.id(), PaymentMethod.PIX)))
                    .hasFieldOrPropertyWithValue("code", "CATEGORY_KIND_MISMATCH");
            assertThatThrownBy(() -> create(new CreateTransactionCommand.Expense(common("1.00"), UUID.randomUUID(), food.id(), PaymentMethod.PIX)))
                    .isInstanceOf(AccountNotFoundException.class);
            assertThatThrownBy(() -> create(new CreateTransactionCommand.Expense(common("1.00"), nubank.id(), UUID.randomUUID(), PaymentMethod.PIX)))
                    .isInstanceOf(CategoryNotFoundException.class);
            verify(transactions, never()).insert(any());
            verify(ledger, never()).append(any(), any());
        }
    }

    @Nested
    @DisplayName("consultar e flags (S-88, S-89)")
    class ConsultarEFlags {

        @Test
        @DisplayName("get devolve o mesmo impacto; inexistente é 404")
        void deveConsultar() {
            Transaction expense = create(new CreateTransactionCommand.Expense(common("100.00"), nubank.id(), food.id(), PaymentMethod.PIX)).transaction();
            when(transactions.findById(USER_ID, expense.id())).thenReturn(Optional.of(new Persisted<>(expense, NOW, NOW)));
            GetTransactionUseCaseImpl get = new GetTransactionUseCaseImpl(transactions, new TransactionDetailsAssembler(transactions, accounts, categories));

            assertThat(get.handle(new GetTransactionQuery(USER_ID, expense.id())).impact().cash()).isEqualTo(Money.of("-100.00"));
            assertThatThrownBy(() -> get.handle(new GetTransactionQuery(USER_ID, UUID.randomUUID()))).isInstanceOf(TransactionNotFoundException.class);
        }

        @Test
        @DisplayName("conta arquivada: editable e voidable falsos com ACCOUNT_ARCHIVED; despesa com reembolso ativo: HAS_ACTIVE_REFUNDS")
        void deveCalcularFlags() {
            Transaction transfer = create(new CreateTransactionCommand.Transfer(common("10.00"), nubank.id(), xp.id())).transaction();
            Account archivedXp = Account.restore(xp.id(), USER_ID, "XP", AccountType.CHECKING, null, Money.of("0.00"), TODAY,
                    com.moneylog.core.account.domain.AccountStatus.ARCHIVED, 1);

            TransactionDetails.Flags archived = TransactionFlags.of(transfer, List.of(nubank, archivedXp), false);
            assertThat(archived).isEqualTo(new TransactionDetails.Flags(false, false, VoidBlockedBy.ACCOUNT_ARCHIVED));

            Transaction expense = create(new CreateTransactionCommand.Expense(common("100.00"), nubank.id(), food.id(), PaymentMethod.PIX)).transaction();
            assertThat(TransactionFlags.of(expense, List.of(nubank), true)).isEqualTo(new TransactionDetails.Flags(true, false, VoidBlockedBy.HAS_ACTIVE_REFUNDS));
            TransactionVoiding.voidTransaction(expense, VoidContext.of("engano", NOW, TODAY).withAccounts(List.of(nubank))
                    .withActiveRefunds(Money.of("0.00")));
            assertThat(TransactionFlags.of(expense, List.of(nubank), false)).isEqualTo(new TransactionDetails.Flags(false, false, null));
            assertThat(TransactionImpactCalculator.of(expense, java.util.Map.of(nubank.id(), nubank)).cash()).isEqualTo(Money.of("0.00"));
        }
    }
}
