package com.moneylog.core.quickentry.app;

import static com.moneylog.core.util.Ids.USER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
import com.moneylog.core.account.util.factory.AccountTestFactory;
import com.moneylog.core.category.app.gateways.CategoryGateway;
import com.moneylog.core.category.domain.Category;
import com.moneylog.core.category.domain.CategoryKind;
import com.moneylog.core.category.util.factory.CategoryTestFactory;
import com.moneylog.core.finance.app.transactions.AccountTransactionFactory;
import com.moneylog.core.finance.app.transactions.TransactionDetails;
import com.moneylog.core.finance.app.transactions.TransactionDetailsAssembler;
import com.moneylog.core.finance.app.transactions.usecase.CreateTransactionCommand;
import com.moneylog.core.finance.app.transactions.usecase.CreateTransactionUseCase;
import com.moneylog.core.quickentry.app.gateways.QuickEntryAuditGateway;
import com.moneylog.core.quickentry.app.gateways.QuickEntryUsageGateway;
import com.moneylog.core.quickentry.app.gateways.QuickEntryUsageGateway.Usage;
import com.moneylog.core.quickentry.app.gateways.UserTimeZoneGateway;
import com.moneylog.core.quickentry.domain.QuickAmountInput;
import com.moneylog.core.quickentry.domain.QuickEntryAudit;
import com.moneylog.core.quickentry.domain.QuickEntryKind;
import com.moneylog.core.quickentry.exception.QuickEntryErrorCodes;
import com.moneylog.core.sk.id.IdGenerator;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.persistence.Persisted;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.core.transaction.app.gateways.IdempotencyGateway;
import com.moneylog.core.transaction.app.gateways.IdempotencyGateway.Claim;
import com.moneylog.core.transaction.app.gateways.TransactionGateway;
import com.moneylog.core.transaction.domain.PaymentMethod;
import com.moneylog.core.transaction.domain.Transaction;
import com.moneylog.core.transaction.domain.TransactionOrigin;
import com.moneylog.core.transaction.domain.TransactionSource;
import com.moneylog.core.transaction.exception.TransactionExceptions.IdempotencyKeyReusedException;
import com.moneylog.core.transaction.exception.TransactionExceptions.TransactionNotFoundException;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Caso de uso do lançamento rápido (T5)")
class CreateQuickEntryUseCaseImplTest {

    private static final Instant NOW = Instant.parse("2026-10-21T02:30:00Z");
    private static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");
    private static final UUID NEW_ID = UUID.fromString("00000000-0000-0000-0000-0000000e0001");
    private static final UUID TOKEN_ID = UUID.fromString("00000000-0000-0000-0000-0000000a0001");
    private static final String KEY = "20261020233000";
    private static final String SCOPE = "POST /v1/quick-entries";

    @Mock
    private CreateTransactionUseCase createTransaction;
    @Mock
    private TransactionGateway transactions;
    @Mock
    private IdempotencyGateway idempotency;
    @Mock
    private AccountGateway accounts;
    @Mock
    private CategoryGateway categories;
    @Mock
    private QuickEntryAuditGateway audits;
    @Mock
    private UserTimeZoneGateway timeZones;
    @Mock
    private QuickEntryUsageGateway usage;
    @Mock
    private IdGenerator ids;
    @Mock
    private TimeProvider time;

    private final Account wallet = AccountTestFactory.umaContaDoTipo(AccountType.WALLET);
    private final Category food = CategoryTestFactory.umaCategoriaDeDespesa();
    private final Category salary = CategoryTestFactory.umaCategoriaDeReceita();
    private final Category others = Category.createSystem(UUID.fromString("00000000-0000-0000-0000-00000000c0ff"), USER_ID, "Outros", CategoryKind.EXPENSE);
    private TransactionDetailsAssembler assembler;
    private AccountTransactionFactory factory;
    private UUID resolvedKey;

    @BeforeEach
    void setUp() {
        when(ids.next()).thenReturn(NEW_ID);
        when(time.now()).thenReturn(NOW);
        when(timeZones.timeZoneOf(USER_ID)).thenReturn(SAO_PAULO);
        when(accounts.findById(USER_ID, wallet.id())).thenReturn(Optional.of(new Persisted<>(wallet, NOW, NOW)));
        when(categories.findById(USER_ID, food.id())).thenReturn(Optional.of(food));
        when(categories.findById(USER_ID, salary.id())).thenReturn(Optional.of(salary));
        when(categories.findById(USER_ID, others.id())).thenReturn(Optional.of(others));
        when(categories.list(USER_ID, CategoryKind.EXPENSE, false)).thenReturn(List.of(food, others));
        when(categories.list(USER_ID, CategoryKind.INCOME, false)).thenReturn(List.of(salary));
        when(categories.systemKeysById(USER_ID)).thenReturn(Map.of(food.id(), "FOOD", salary.id(), "SALARY", others.id(), "OTHER_EXPENSE"));
        when(idempotency.claim(any(), anyString(), any(), anyString(), any(), any(), any())).thenReturn(true);
        when(transactions.sumActiveRefundsOf(any(), any())).thenReturn(Money.of("0.00"));
        when(usage.activeUsage(any(), any(), any())).thenReturn(new Usage(0, zero(), zero()));
        assembler = new TransactionDetailsAssembler(transactions, accounts, categories);
        factory = new AccountTransactionFactory(transactions, accounts, categories);
        when(createTransaction.handleClaimed(any(), any(), any())).thenAnswer(call -> {
            CreateTransactionCommand command = call.getArgument(0);
            Transaction built = factory.build(command, call.getArgument(1), call.getArgument(2), null);
            return assembler.assemble(new Persisted<>(built, NOW, NOW));
        });
        resolvedKey = com.moneylog.core.quickentry.domain.QuickIdempotencyKey.resolve(USER_ID, KEY);
    }

    private static Money zero() {
        return Money.zero(CurrencyCode.BRL);
    }

    private CreateQuickEntryUseCaseImpl useCase(QuickEntryLimits limits) {
        return new CreateQuickEntryUseCaseImpl(createTransaction, transactions, assembler, idempotency, accounts, categories, audits, timeZones,
                usage, ids, time, limits);
    }

    private CreateQuickEntryCommand expense(String amount, String category, LocalDate date) {
        return new CreateQuickEntryCommand(USER_ID, TOKEN_ID, wallet.id(), KEY, QuickEntryKind.EXPENSE, new QuickAmountInput.Text(amount), category,
                "Almoço", null, date, "203.0.113.0");
    }

    private CreateQuickEntryCommand income(String amount, String category) {
        return new CreateQuickEntryCommand(USER_ID, TOKEN_ID, wallet.id(), KEY, QuickEntryKind.INCOME, new QuickAmountInput.Text(amount), category,
                null, null, null, "203.0.113.0");
    }

    @Test
    @DisplayName("gasto: um claim no escopo do canal ANTES de criar, cria pela mesma criação sem claim, com origem QUICK_SHORTCUT, e audita depois")
    void deveCriarGastoPeloMesmoCaminho() {
        CreateQuickEntryResult result = useCase(QuickEntryLimits.none()).handle(expense("100", "Alimentação", LocalDate.of(2026, 10, 20)));

        InOrder order = inOrder(idempotency, createTransaction, audits);
        order.verify(idempotency).claim(eq(USER_ID), eq(SCOPE), eq(resolvedKey), anyString(), eq(NEW_ID), eq(NOW), eq(NOW.minusSeconds(24 * 3600)));
        ArgumentCaptor<CreateTransactionCommand> command = ArgumentCaptor.forClass(CreateTransactionCommand.class);
        order.verify(createTransaction).handleClaimed(command.capture(), eq(NEW_ID), eq(TransactionOrigin.quickShortcut()));
        order.verify(audits).insert(new QuickEntryAudit(NEW_ID, TOKEN_ID, USER_ID, "203.0.113.0", NOW));
        verify(createTransaction, never()).handle(any(), any());
        verify(createTransaction, never()).handle(any());

        var expense = (CreateTransactionCommand.Expense) command.getValue();
        assertThat(expense.accountId()).isEqualTo(wallet.id());
        assertThat(expense.categoryId()).isEqualTo(food.id());
        assertThat(expense.method()).isEqualTo(PaymentMethod.OTHER);
        assertThat(expense.common().amount()).isEqualTo(Money.of("100.00"));
        assertThat(expense.common().date()).isEqualTo(LocalDate.of(2026, 10, 20));
        assertThat(result.message()).isEqualTo("Gasto de R$ 100,00 em Alimentação (" + wallet.name() + ") registrado.");
        assertThat(result.details().transaction().origin().source()).isEqualTo(TransactionSource.QUICK_SHORTCUT);
    }

    @Test
    @DisplayName("entrada de 2500,00 em Salário: cria INCOME na conta do token, com o método padrão OTHER do servidor")
    void deveCriarEntrada() {
        CreateQuickEntryResult result = useCase(QuickEntryLimits.none()).handle(income("2500,00", "salario"));

        ArgumentCaptor<CreateTransactionCommand> command = ArgumentCaptor.forClass(CreateTransactionCommand.class);
        verify(createTransaction).handleClaimed(command.capture(), eq(NEW_ID), eq(TransactionOrigin.quickShortcut()));
        var income = (CreateTransactionCommand.Income) command.getValue();
        assertThat(income.categoryId()).isEqualTo(salary.id());
        assertThat(income.method()).isEqualTo(PaymentMethod.OTHER);
        assertThat(result.message()).isEqualTo("Entrada de R$ 2.500,00 em Salário (" + wallet.name() + ") registrada.");
    }

    @Test
    @DisplayName("method do gasto segue para a criação; descrição não aparece na mensagem")
    void deveRepassarOMetodoENaoEcoarADescricao() {
        CreateQuickEntryCommand command = new CreateQuickEntryCommand(USER_ID, TOKEN_ID, wallet.id(), KEY, QuickEntryKind.EXPENSE,
                new QuickAmountInput.JsonNumber("27.6"), "FOOD", "segredo do almoço", PaymentMethod.PIX, null, null);

        CreateQuickEntryResult result = useCase(QuickEntryLimits.none()).handle(command);

        ArgumentCaptor<CreateTransactionCommand> captured = ArgumentCaptor.forClass(CreateTransactionCommand.class);
        verify(createTransaction).handleClaimed(captured.capture(), any(), any());
        assertThat(((CreateTransactionCommand.Expense) captured.getValue()).method()).isEqualTo(PaymentMethod.PIX);
        assertThat(result.message()).contains("R$ 27,60").doesNotContain("segredo");
        verify(audits).insert(new QuickEntryAudit(NEW_ID, TOKEN_ID, USER_ID, null, NOW));
    }

    @Test
    @DisplayName("income com method é recusado no comando")
    void deveRecusarMetodoNaEntrada() {
        assertThatThrownBy(() -> new CreateQuickEntryCommand(USER_ID, TOKEN_ID, wallet.id(), KEY, QuickEntryKind.INCOME,
                new QuickAmountInput.Text("1"), null, null, PaymentMethod.PIX, null, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("category omitida: categoryId nulo, e a criação aplica OTHER_EXPENSE/OTHER_INCOME; nada de busca de categoria")
    void deveDeixarACategoriaPadraoParaACriacao() {
        when(categories.findBySystemKey(USER_ID, "OTHER_EXPENSE")).thenReturn(Optional.of(others));

        CreateQuickEntryResult result = useCase(QuickEntryLimits.none()).handle(expense("12,90", null, null));

        ArgumentCaptor<CreateTransactionCommand> captured = ArgumentCaptor.forClass(CreateTransactionCommand.class);
        verify(createTransaction).handleClaimed(captured.capture(), any(), any());
        assertThat(((CreateTransactionCommand.Expense) captured.getValue()).categoryId()).isNull();
        verify(categories, never()).list(any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
        assertThat(result.details().category()).isEqualTo(others);
        assertThat(result.message()).contains("em Outros");
    }

    @Test
    @DisplayName("sem a categoria padrão disponível: DEFAULT_CATEGORY_UNAVAILABLE e nada gravado")
    void deveFalharSemACategoriaPadrao() {
        when(categories.findBySystemKey(USER_ID, "OTHER_EXPENSE")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase(QuickEntryLimits.none()).handle(expense("12,90", null, null)))
                .hasFieldOrPropertyWithValue("code", "DEFAULT_CATEGORY_UNAVAILABLE");
        verify(audits, never()).insert(any());
    }

    @Test
    @DisplayName("categoria inexistente, arquivada ou de outro usuário: QUICK_CATEGORY_NOT_FOUND e nada gravado")
    void deveRecusarCategoriaDesconhecida() {
        assertThatThrownBy(() -> useCase(QuickEntryLimits.none()).handle(expense("10", "Viagem", null)))
                .hasFieldOrPropertyWithValue("code", QuickEntryErrorCodes.QUICK_CATEGORY_NOT_FOUND);
        verify(createTransaction, never()).handleClaimed(any(), any(), any());
        verify(audits, never()).insert(any());
    }

    @Test
    @DisplayName("categoria de receita enviada num gasto não resolve")
    void deveRecusarCategoriaDoOutroTipo() {
        assertThatThrownBy(() -> useCase(QuickEntryLimits.none()).handle(expense("10", "Salário", null)))
                .hasFieldOrPropertyWithValue("code", QuickEntryErrorCodes.QUICK_CATEGORY_NOT_FOUND);
    }

    @Test
    @DisplayName("date omitida às 23:30 de Brasília vale a data de Brasília, não a de UTC")
    void deveUsarOFusoDoUsuario() {
        when(time.now()).thenReturn(Instant.parse("2026-10-21T02:30:00Z"));

        useCase(QuickEntryLimits.none()).handle(expense("10", "FOOD", null));

        ArgumentCaptor<CreateTransactionCommand> captured = ArgumentCaptor.forClass(CreateTransactionCommand.class);
        verify(createTransaction).handleClaimed(captured.capture(), any(), any());
        assertThat(((CreateTransactionCommand.Expense) captured.getValue()).common().date()).isEqualTo(LocalDate.of(2026, 10, 20));
    }

    @Test
    @DisplayName("janela da date: hoje e hoje-366 passam; amanhã e hoje-367 são QUICK_DATE_OUT_OF_RANGE")
    void deveValidarAJanelaDeDatas() {
        LocalDate today = LocalDate.of(2026, 10, 20);
        useCase(QuickEntryLimits.none()).handle(expense("10", "FOOD", today));
        useCase(QuickEntryLimits.none()).handle(expense("10", "FOOD", today.minusDays(366)));
        for (LocalDate bad : List.of(today.plusDays(1), today.minusDays(367))) {
            assertThatThrownBy(() -> useCase(QuickEntryLimits.none()).handle(expense("10", "FOOD", bad)))
                    .hasFieldOrPropertyWithValue("code", QuickEntryErrorCodes.QUICK_DATE_OUT_OF_RANGE);
        }
    }

    @Test
    @DisplayName("valor inválido ou ambíguo: erro do parser e nada gravado")
    void deveRecusarValorRuim() {
        assertThatThrownBy(() -> useCase(QuickEntryLimits.none()).handle(expense("1.234", "FOOD", null)))
                .hasFieldOrPropertyWithValue("code", QuickEntryErrorCodes.QUICK_AMOUNT_AMBIGUOUS);
        assertThatThrownBy(() -> useCase(QuickEntryLimits.none()).handle(expense("abc", "FOOD", null)))
                .hasFieldOrPropertyWithValue("code", QuickEntryErrorCodes.QUICK_AMOUNT_INVALID);
        verify(createTransaction, never()).handleClaimed(any(), any(), any());
        verify(audits, never()).insert(any());
    }

    @Test
    @DisplayName("conta do token arquivada ou de investimento: QUICK_ACCOUNT_NOT_ALLOWED e nada gravado")
    void deveRecusarContaNaoPermitida() {
        for (Account bad : List.of(AccountTestFactory.umaContaArquivada(), AccountTestFactory.umaContaDeInvestimento())) {
            when(accounts.findById(USER_ID, bad.id())).thenReturn(Optional.of(new Persisted<>(bad, NOW, NOW)));
            CreateQuickEntryCommand command = new CreateQuickEntryCommand(USER_ID, TOKEN_ID, bad.id(), KEY, QuickEntryKind.EXPENSE,
                    new QuickAmountInput.Text("10"), null, null, null, null, null);
            assertThatThrownBy(() -> useCase(QuickEntryLimits.none()).handle(command))
                    .hasFieldOrPropertyWithValue("code", QuickEntryErrorCodes.QUICK_ACCOUNT_NOT_ALLOWED);
        }
        verify(createTransaction, never()).handleClaimed(any(), any(), any());
    }

    @Test
    @DisplayName("sem nenhum teto: valor alto até o limite técnico é aceito e o lock e a contagem do dia nem são consultados")
    void deveAceitarSemTetoENaoSerializar() {
        useCase(QuickEntryLimits.none()).handle(expense("999.999.999,99", "FOOD", null));

        verify(createTransaction).handleClaimed(any(), any(), any());
        verifyNoInteractions(usage);
    }

    @Test
    @DisplayName("teto por lançamento configurado: acima dele QUICK_LIMIT_REACHED; no teto passa; sem lock")
    void deveAplicarOTetoPorLancamento() {
        QuickEntryLimits limits = new QuickEntryLimits(Money.of("1000.00"), 0, null, null);

        useCase(limits).handle(expense("1.000,00", "FOOD", null));
        assertThatThrownBy(() -> useCase(limits).handle(expense("1.000,01", "FOOD", null)))
                .hasFieldOrPropertyWithValue("code", QuickEntryErrorCodes.QUICK_LIMIT_REACHED)
                .hasMessageContaining("R$ 1.000,00");
        verifyNoInteractions(usage);
    }

    @Test
    @DisplayName("teto de quantidade por dia: trava o usuário, conta no dia do fuso pelo instante de criação e recusa o excedente")
    void deveAplicarOTetoDeQuantidade() {
        QuickEntryLimits limits = new QuickEntryLimits(null, 30, null, null);
        when(usage.activeUsage(any(), any(), any())).thenReturn(new Usage(29, zero(), zero()), new Usage(30, zero(), zero()));

        useCase(limits).handle(expense("10", "FOOD", null));
        assertThatThrownBy(() -> useCase(limits).handle(expense("10", "FOOD", null)))
                .hasFieldOrPropertyWithValue("code", QuickEntryErrorCodes.QUICK_LIMIT_REACHED).hasMessageContaining("30 lançamentos");

        verify(usage, org.mockito.Mockito.times(2)).lockUser(USER_ID);
        verify(usage, org.mockito.Mockito.times(2)).activeUsage(USER_ID, Instant.parse("2026-10-20T03:00:00Z"), Instant.parse("2026-10-21T03:00:00Z"));
    }

    @Test
    @DisplayName("teto de valor do dia: separado por tipo; gasto cheio não bloqueia entrada")
    void deveAplicarOTetoDeValorPorTipo() {
        QuickEntryLimits limits = new QuickEntryLimits(null, 0, Money.of("3000.00"), Money.of("1000.00"));
        when(usage.activeUsage(any(), any(), any())).thenReturn(new Usage(5, Money.of("2999.00"), Money.of("0.00")));

        assertThatThrownBy(() -> useCase(limits).handle(expense("1,01", "FOOD", null)))
                .hasFieldOrPropertyWithValue("code", QuickEntryErrorCodes.QUICK_LIMIT_REACHED).hasMessageContaining("gastos");
        useCase(limits).handle(expense("1,00", "FOOD", null));
        useCase(limits).handle(income("1.000,00", "SALARY"));
        assertThatThrownBy(() -> useCase(limits).handle(income("1.000,01", "SALARY")))
                .hasFieldOrPropertyWithValue("code", QuickEntryErrorCodes.QUICK_LIMIT_REACHED).hasMessageContaining("entradas");
    }

    @Test
    @DisplayName("mesma chave e mesmo input: replay do recurso gravado, sem criar, sem auditar de novo, sem revalidar a data")
    void deveFazerReplay() {
        CreateQuickEntryCommand command = expense("100", "Alimentação", null);
        Transaction existing = factory.build(new CreateTransactionCommand.Expense(
                new CreateTransactionCommand.Common(USER_ID, Money.of("100.00"), LocalDate.of(2026, 10, 20), "Almoço", null), wallet.id(),
                food.id(), PaymentMethod.OTHER), NEW_ID, TransactionOrigin.quickShortcut(), null);
        CreateQuickEntryUseCaseImpl useCase = useCase(QuickEntryLimits.none());
        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        useCase.handle(command);
        verify(idempotency).claim(any(), anyString(), any(), hash.capture(), any(), any(), any());

        when(idempotency.claim(any(), anyString(), any(), anyString(), any(), any(), any())).thenReturn(false);
        when(idempotency.find(USER_ID, SCOPE, resolvedKey)).thenReturn(Optional.of(new Claim(hash.getValue(), NEW_ID, NOW)));
        when(transactions.findById(USER_ID, NEW_ID)).thenReturn(Optional.of(new Persisted<>(existing, NOW, NOW)));
        when(time.now()).thenReturn(Instant.parse("2026-10-23T12:00:00Z"));

        CreateQuickEntryResult replay = useCase.handle(command);

        assertThat(replay.details().transaction().id()).isEqualTo(NEW_ID);
        assertThat(replay.message()).isEqualTo("Gasto de R$ 100,00 em Alimentação (" + wallet.name() + ") registrado.");
        verify(createTransaction).handleClaimed(any(), any(), any());
        verify(audits).insert(any());
    }

    @Test
    @DisplayName("mesma chave com input diferente (inclusive só a data ou o texto do valor): IDEMPOTENCY_KEY_REUSED e nada gravado")
    void deveRecusarChaveReutilizada() {
        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        useCase(QuickEntryLimits.none()).handle(expense("100", "Alimentação", null));
        verify(idempotency).claim(any(), anyString(), any(), hash.capture(), any(), any(), any());
        when(idempotency.claim(any(), anyString(), any(), anyString(), any(), any(), any())).thenReturn(false);
        when(idempotency.find(USER_ID, SCOPE, resolvedKey)).thenReturn(Optional.of(new Claim(hash.getValue(), NEW_ID, NOW)));

        for (CreateQuickEntryCommand other : List.of(expense("100,00", "Alimentação", null), expense("100", "Alimentação", LocalDate.of(2026, 10, 20)),
                expense("100", "Lazer", null), expense("101", "Alimentação", null), income("100", "Alimentação"))) {
            assertThatThrownBy(() -> useCase(QuickEntryLimits.none()).handle(other)).isInstanceOf(IdempotencyKeyReusedException.class);
        }
        verify(createTransaction).handleClaimed(any(), any(), any());
    }

    @Test
    @DisplayName("o hash vem do input bruto: 27.6 número e 27.6 texto são o mesmo pedido, mas outra conta do token é outro pedido")
    void deveCalcularOHashSobreOInputBruto() {
        ArgumentCaptor<String> hashes = ArgumentCaptor.forClass(String.class);
        CreateQuickEntryUseCaseImpl useCase = useCase(QuickEntryLimits.none());
        useCase.handle(expense("27.6", "FOOD", null));
        useCase.handle(new CreateQuickEntryCommand(USER_ID, TOKEN_ID, wallet.id(), KEY, QuickEntryKind.EXPENSE, new QuickAmountInput.JsonNumber("27.6"),
                "FOOD", "Almoço", null, null, "198.51.100.0"));
        Account other = AccountTestFactory.umaContaSemSaldo();
        when(accounts.findById(USER_ID, other.id())).thenReturn(Optional.of(new Persisted<>(other, NOW, NOW)));
        useCase.handle(new CreateQuickEntryCommand(USER_ID, TOKEN_ID, other.id(), KEY, QuickEntryKind.EXPENSE, new QuickAmountInput.Text("27.6"),
                "FOOD", "Almoço", null, null, null));

        verify(idempotency, org.mockito.Mockito.times(3)).claim(any(), anyString(), any(), hashes.capture(), any(), any(), any());
        assertThat(hashes.getAllValues().get(0)).isEqualTo(hashes.getAllValues().get(1)).isNotEqualTo(hashes.getAllValues().get(2));
    }

    @Test
    @DisplayName("recurso da chave sumiu: TRANSACTION_NOT_FOUND")
    void deveFalharSeORecursoSumiu() {
        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        CreateQuickEntryCommand command = expense("100", "Alimentação", null);
        useCase(QuickEntryLimits.none()).handle(command);
        verify(idempotency).claim(any(), anyString(), any(), hash.capture(), any(), any(), any());
        when(idempotency.claim(any(), anyString(), any(), anyString(), any(), any(), any())).thenReturn(false);
        when(idempotency.find(USER_ID, SCOPE, resolvedKey)).thenReturn(Optional.of(new Claim(hash.getValue(), NEW_ID, NOW)));
        when(transactions.findById(USER_ID, NEW_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase(QuickEntryLimits.none()).handle(command)).isInstanceOf(TransactionNotFoundException.class);
    }

    @Test
    @DisplayName("chave fora do formato é erro de programação (o chamador valida antes)")
    void deveRecusarChaveForaDoFormato() {
        CreateQuickEntryCommand command = new CreateQuickEntryCommand(USER_ID, TOKEN_ID, wallet.id(), "curta", QuickEntryKind.EXPENSE,
                new QuickAmountInput.Text("10"), null, null, null, null, null);
        assertThatThrownBy(() -> useCase(QuickEntryLimits.none()).handle(command)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(idempotency);
    }

    @Test
    @DisplayName("limites: zero ou negativo vira desligado")
    void deveTratarZeroComoDesligado() {
        QuickEntryLimits limits = new QuickEntryLimits(Money.zero(CurrencyCode.BRL), 0, Money.of("0.00"), null);
        assertThat(limits.maxAmount()).isNull();
        assertThat(limits.hasDailyLimit()).isFalse();
        assertThat(QuickEntryLimits.none().hasDailyLimit()).isFalse();
        assertThat(new QuickEntryLimits(null, 1, null, null).hasDailyLimit()).isTrue();
    }
}
