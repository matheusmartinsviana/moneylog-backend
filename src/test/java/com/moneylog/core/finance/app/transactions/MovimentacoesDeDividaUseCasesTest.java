package com.moneylog.core.finance.app.transactions;

import static com.moneylog.core.util.Ids.USER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
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
import com.moneylog.core.account.util.factory.AccountTestFactory;
import com.moneylog.core.category.app.gateways.CategoryGateway;
import com.moneylog.core.category.domain.Category;
import com.moneylog.core.category.util.factory.CategoryTestFactory;
import com.moneylog.core.debt.app.DebtSummary;
import com.moneylog.core.debt.domain.Debt;
import com.moneylog.core.debt.domain.DebtDirection;
import com.moneylog.core.debt.domain.DebtStatus;
import com.moneylog.core.debt.domain.Person;
import com.moneylog.core.debt.exception.DebtExceptions.DebtDirectionMismatchException;
import com.moneylog.core.debt.exception.DebtExceptions.DebtNotFoundException;
import com.moneylog.core.debt.exception.DebtExceptions.DebtOperationRefusedException;
import com.moneylog.core.debt.exception.DebtExceptions.PersonArchivedException;
import com.moneylog.core.debt.exception.DebtExceptions.PersonNotFoundException;
import com.moneylog.core.debt.exception.DebtExceptions.SettlementExceedsOutstandingException;
import com.moneylog.core.debt.util.InMemoryDebtGateway;
import com.moneylog.core.debt.util.InMemoryPersonGateway;
import com.moneylog.core.debt.util.factory.PersonTestFactory;
import com.moneylog.core.finance.app.gateways.LedgerWriteGateway;
import com.moneylog.core.finance.app.transactions.TransactionDetails.Flags;
import com.moneylog.core.finance.app.transactions.usecase.CreateTransactionCommand;
import com.moneylog.core.finance.app.transactions.usecase.CreateTransactionCommand.Common;
import com.moneylog.core.finance.app.transactions.usecase.CreateTransactionCommand.DebtPayment;
import com.moneylog.core.finance.app.transactions.usecase.CreateTransactionCommand.Expense;
import com.moneylog.core.finance.app.transactions.usecase.CreateTransactionCommand.LoanGiven;
import com.moneylog.core.finance.app.transactions.usecase.CreateTransactionCommand.MoneyReceived;
import com.moneylog.core.finance.app.transactions.usecase.CreateTransactionCommand.NewDebtSpec;
import com.moneylog.core.finance.app.transactions.usecase.CreateTransactionCommand.Share;
import com.moneylog.core.finance.app.transactions.usecase.CreateTransactionUseCaseImpl;
import com.moneylog.core.finance.app.transactions.usecase.UpdateTransactionUseCase.UpdateTransactionCommand;
import com.moneylog.core.finance.app.transactions.usecase.UpdateTransactionUseCaseImpl;
import com.moneylog.core.finance.app.transactions.usecase.VoidTransactionUseCase.VoidTransactionCommand;
import com.moneylog.core.finance.app.transactions.usecase.VoidTransactionUseCaseImpl;
import com.moneylog.core.finance.domain.FinancialRecords;
import com.moneylog.core.sk.id.IdGenerator;
import com.moneylog.core.sk.ledger.PostingSourceType;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.persistence.Persisted;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.core.transaction.app.gateways.IdempotencyGateway;
import com.moneylog.core.transaction.app.gateways.TransactionGateway;
import com.moneylog.core.transaction.app.gateways.TransactionRevisionGateway;
import com.moneylog.core.transaction.domain.PaymentMethod;
import com.moneylog.core.transaction.domain.Transaction;
import com.moneylog.core.transaction.domain.TransactionStatus;
import com.moneylog.core.transaction.domain.TransactionType;
import com.moneylog.core.transaction.exception.TransactionExceptions.AccountNotCashException;
import com.moneylog.core.transaction.exception.TransactionExceptions.SharesExceedAmountException;
import com.moneylog.core.transaction.exception.TransactionExceptions.TransactionFieldRequiredException;
import com.moneylog.core.transaction.exception.TransactionExceptions.TransactionNotEditableException;
import com.moneylog.core.transaction.exception.TransactionExceptions.TransactionVoidRefusedException;

/**
 * T76 e T78: emprestar, receber, pagar e dividir despesa pelo mesmo caminho dos demais tipos (handlers por tipo, fábrica, caso de uso
 * de criação, estorno e edição), com as dívidas gravadas antes da movimentação e as guardas de estorno de S-141.
 */
@DisplayName("Movimentações de dívida e despesa dividida: casos de uso (T76, T78)")
class MovimentacoesDeDividaUseCasesTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 10);
    private static final Instant NOW = Instant.parse("2026-10-10T15:00:00Z");

    private final TransactionGateway transactions = mock(TransactionGateway.class);
    private final AccountGateway accounts = mock(AccountGateway.class);
    private final CategoryGateway categories = mock(CategoryGateway.class);
    private final LedgerWriteGateway ledger = mock(LedgerWriteGateway.class);
    private final IdempotencyGateway idempotency = mock(IdempotencyGateway.class);
    private final TransactionRevisionGateway revisions = mock(TransactionRevisionGateway.class);
    private final TimeProvider time = mock(TimeProvider.class);
    private final InMemoryPersonGateway people = new InMemoryPersonGateway();
    private final InMemoryDebtGateway debts = spy(new InMemoryDebtGateway());

    private final Account nubank = AccountTestFactory.umaContaCorrente();
    private final Account invest = AccountTestFactory.umaContaDeInvestimento();
    private final Category food = CategoryTestFactory.umaCategoriaDeDespesa();
    private final Person joao = PersonTestFactory.umJoao();
    private final Person pedro = PersonTestFactory.umPedro();
    private final List<Transaction> stored = new ArrayList<>();
    private int sequence = 1;
    private final IdGenerator ids = () -> UUID.fromString(String.format("00000000-0000-0000-0000-0000000c%04d", sequence++));

    private CreateTransactionUseCaseImpl create;
    private VoidTransactionUseCaseImpl voidUseCase;
    private UpdateTransactionUseCaseImpl update;
    private TransactionDetailsAssembler assembler;

    @BeforeEach
    void setUp() {
        when(time.today()).thenReturn(TODAY);
        when(time.now()).thenReturn(NOW);
        when(accounts.findById(USER_ID, nubank.id())).thenReturn(Optional.of(new Persisted<>(nubank, NOW, NOW)));
        when(accounts.findById(USER_ID, invest.id())).thenReturn(Optional.of(new Persisted<>(invest, NOW, NOW)));
        when(categories.findById(USER_ID, food.id())).thenReturn(Optional.of(food));
        when(categories.list(USER_ID, null, true)).thenReturn(List.of(food));
        when(transactions.sumActiveRefundsOf(any(), any())).thenReturn(Money.of("0.00"));
        when(transactions.insert(any())).thenAnswer(call -> {
            Transaction tx = call.getArgument(0);
            stored.add(tx);
            return new Persisted<>(tx, NOW, NOW);
        });
        when(transactions.update(any())).thenAnswer(call -> new Persisted<>(call.getArgument(0), NOW, NOW));
        when(transactions.findById(any(), any())).thenAnswer(call -> stored.stream().filter(t -> t.id().equals(call.getArgument(1)))
                .findFirst().map(t -> new Persisted<>(t, NOW, NOW)));
        people.insert(joao);
        people.insert(pedro);
        assembler = new TransactionDetailsAssembler(transactions, accounts, categories, null, time, debts, people);
        AccountTransactionFactory factory = new AccountTransactionFactory(transactions, accounts, categories, null, time, people, debts, ids);
        create = new CreateTransactionUseCaseImpl(transactions, factory, ledger, ids, assembler, idempotency, time, debts);
        voidUseCase = new VoidTransactionUseCaseImpl(transactions, accounts, null, ledger, assembler, time, debts);
        update = new UpdateTransactionUseCaseImpl(transactions, accounts, factory, revisions, ledger, assembler, time);
    }

    private Common common(String amount) {
        return new Common(USER_ID, Money.of(amount), TODAY, "Movimentação", null);
    }

    private TransactionDetails loan(String amount, Person person) {
        return create.handle(new LoanGiven(common(amount), nubank.id(), new NewDebtSpec(person.id(), LocalDate.of(2026, 11, 5), null), null), null);
    }

    private TransactionDetails received(String amount, UUID debtId) {
        return create.handle(new MoneyReceived(common(amount), nubank.id(), debtId, null), null);
    }

    private TransactionDetails split(String amount, List<Share> shares) {
        return create.handle(new Expense(common(amount), nubank.id(), food.id(), PaymentMethod.PIX, shares), null);
    }

    private Share share(String amount, Person person) {
        return new Share(Money.of(amount), new NewDebtSpec(person.id(), null, null));
    }

    private VoidTransactionCommand voidCommand(Transaction tx) {
        return new VoidTransactionCommand(USER_ID, tx.id(), "motivo");
    }

    // ---------------------------------------------------------------- emprestar, receber, pagar

    @Test
    @DisplayName("emprestar 300 ao João: a dívida RECEIVABLE é gravada ANTES da movimentação, os lançamentos depois; caixa −300, consumo vazio, patrimônio 0")
    void deveEmprestar() {
        TransactionDetails details = loan("300.00", joao);
        Transaction tx = details.transaction();
        assertThat(tx.type()).isEqualTo(TransactionType.LOAN_GIVEN);
        assertThat(tx.debtId()).isNotNull();
        assertThat(details.debts()).hasSize(1);
        DebtSummary summary = details.debts().get(0);
        assertThat(summary.direction()).isEqualTo(DebtDirection.RECEIVABLE);
        assertThat(summary.principal()).isEqualTo(Money.of("300.00"));
        assertThat(summary.outstanding()).isEqualTo(Money.of("300.00"));
        assertThat(summary.personName()).isEqualTo("João");
        assertThat(summary.description()).isEqualTo("Movimentação");
        assertThat(summary.expectedDate()).isEqualTo(LocalDate.of(2026, 11, 5));
        assertThat(details.impact().cash()).isEqualTo(Money.of("-300.00"));
        assertThat(details.impact().consumption()).isEmpty();
        assertThat(details.impact().netWorth()).isEqualTo(Money.of("0.00"));
        assertThat(details.flags()).isEqualTo(new Flags(false, true, null));

        InOrder order = inOrder(debts, transactions, ledger);
        order.verify(debts).insert(any(Debt.class));
        order.verify(transactions).insert(any(Transaction.class));
        order.verify(ledger).append(eq(USER_ID), any());
        assertThat(people.locks()).isEqualTo(1);
        assertThat(debts.findById(USER_ID, tx.debtId()).orElseThrow().value().originTransactionId()).isEqualTo(tx.id());
    }

    @Test
    @DisplayName("receber: quita a dívida sem receita; passar do saldo, direção errada, dívida inexistente e conta que não é CASH são recusados")
    void deveReceberADevolucao() {
        UUID debtId = loan("300.00", joao).transaction().debtId();
        TransactionDetails first = received("200.00", debtId);
        assertThat(first.transaction().type()).isEqualTo(TransactionType.MONEY_RECEIVED);
        assertThat(first.debts().get(0).status()).isEqualTo(DebtStatus.PARTIALLY_SETTLED);
        assertThat(first.debts().get(0).outstanding()).isEqualTo(Money.of("100.00"));
        assertThat(first.impact().cash()).isEqualTo(Money.of("200.00"));
        assertThat(first.impact().consumption()).isEmpty();
        assertThat(debts.locked()).contains(debtId);

        assertThatThrownBy(() -> received("300.00", debtId)).isInstanceOf(SettlementExceedsOutstandingException.class);
        assertThat(received("100.00", debtId).debts().get(0).status()).isEqualTo(DebtStatus.SETTLED);
        assertThatThrownBy(() -> received("1.00", UUID.randomUUID())).isInstanceOf(DebtNotFoundException.class);
        assertThatThrownBy(() -> create.handle(new MoneyReceived(common("1.00"), invest.id(), debtId, null), null))
                .isInstanceOf(AccountNotCashException.class);
        UUID payable = create.handle(new MoneyReceived(common("500.00"), nubank.id(), null, new NewDebtSpec(pedro.id(), null, "Captação")), null)
                .transaction().debtId();
        assertThatThrownBy(() -> received("10.00", payable)).isInstanceOf(DebtDirectionMismatchException.class);
    }

    @Test
    @DisplayName("captação: MONEY_RECEIVED com newDebt cria a PAYABLE; sem debtId e sem newDebt (ou com os dois) falta o campo")
    void deveCaptar() {
        TransactionDetails details = create.handle(new MoneyReceived(common("500.00"), nubank.id(), null,
                new NewDebtSpec(pedro.id(), null, "Empréstimo do Pedro")), null);
        assertThat(details.debts().get(0).direction()).isEqualTo(DebtDirection.PAYABLE);
        assertThat(details.debts().get(0).description()).isEqualTo("Empréstimo do Pedro");
        assertThat(details.impact().cash()).isEqualTo(Money.of("500.00"));
        assertThat(details.impact().netWorth()).isEqualTo(Money.of("0.00"));
        assertThatThrownBy(() -> create.handle(new MoneyReceived(common("1.00"), nubank.id(), null, null), null))
                .isInstanceOf(TransactionFieldRequiredException.class);
        assertThatThrownBy(() -> create.handle(new MoneyReceived(common("1.00"), nubank.id(), UUID.randomUUID(),
                new NewDebtSpec(pedro.id(), null, null)), null)).isInstanceOf(TransactionFieldRequiredException.class);
        assertThatThrownBy(() -> create.handle(new LoanGiven(common("1.00"), nubank.id(), null, null), null))
                .isInstanceOf(TransactionFieldRequiredException.class);
    }

    @Test
    @DisplayName("pagar: caixa −, consumo 0, saldo cai; sem debtId falta o campo; RECEIVABLE é direção errada")
    void devePagar() {
        UUID payable = create.handle(new MoneyReceived(common("500.00"), nubank.id(), null, new NewDebtSpec(pedro.id(), null, null)), null)
                .transaction().debtId();
        TransactionDetails paid = create.handle(new DebtPayment(common("200.00"), nubank.id(), payable), null);
        assertThat(paid.transaction().type()).isEqualTo(TransactionType.DEBT_PAYMENT);
        assertThat(paid.debts().get(0).outstanding()).isEqualTo(Money.of("300.00"));
        assertThat(paid.impact().cash()).isEqualTo(Money.of("-200.00"));
        assertThat(paid.impact().consumption()).isEmpty();
        assertThatThrownBy(() -> create.handle(new DebtPayment(common("1.00"), nubank.id(), null), null))
                .isInstanceOf(TransactionFieldRequiredException.class);
        UUID receivable = loan("100.00", joao).transaction().debtId();
        assertThatThrownBy(() -> create.handle(new DebtPayment(common("1.00"), nubank.id(), receivable), null))
                .isInstanceOf(DebtDirectionMismatchException.class);
    }

    @Test
    @DisplayName("origem repetida (DEBT_ALREADY_HAS_ORIGIN), pessoa arquivada, pessoa inexistente: recusados sem gravar a movimentação")
    void deveRecusarEmprestimos() {
        UUID debtId = loan("300.00", joao).transaction().debtId();
        assertThatThrownBy(() -> create.handle(new LoanGiven(common("50.00"), nubank.id(), null, debtId), null))
                .isInstanceOf(DebtOperationRefusedException.class).extracting("code").isEqualTo("DEBT_ALREADY_HAS_ORIGIN");
        Person archived = Person.restore(UUID.fromString("00000000-0000-0000-0000-0000000f00bb"), USER_ID, "Arquivada", null, true);
        people.insert(archived);
        int before = stored.size();
        assertThatThrownBy(() -> loan("10.00", archived)).isInstanceOf(PersonArchivedException.class);
        assertThatThrownBy(() -> create.handle(new LoanGiven(common("10.00"), nubank.id(), new NewDebtSpec(UUID.randomUUID(), null, null), null), null))
                .isInstanceOf(PersonNotFoundException.class);
        assertThat(stored).hasSize(before);
    }

    // ---------------------------------------------------------------- despesa dividida

    @Test
    @DisplayName("despesa dividida de 200 com 100 do João e 40 do Pedro: duas dívidas gravadas antes da despesa; consumo 60; flags de estorno consideram as dívidas")
    void deveDividirADespesa() {
        TransactionDetails details = split("200.00", List.of(share("100.00", joao), share("40.00", pedro)));
        assertThat(details.transaction().type()).isEqualTo(TransactionType.EXPENSE);
        assertThat(details.debts()).hasSize(2);
        assertThat(details.debts()).extracting(DebtSummary::direction).containsOnly(DebtDirection.RECEIVABLE);
        assertThat(details.impact().cash()).isEqualTo(Money.of("-200.00"));
        assertThat(details.impact().consumption()).hasSize(1);
        assertThat(details.impact().consumption().get(0).amount()).isEqualTo(Money.of("60.00"));
        assertThat(details.impact().netWorth()).isEqualTo(Money.of("-60.00"));
        assertThat(details.flags()).isEqualTo(new Flags(true, true, null));
        verify(debts, org.mockito.Mockito.times(2)).insert(any(Debt.class));
        assertThat(people.locks()).isEqualTo(2);

        assertThatThrownBy(() -> split("100.00", List.of(share("100.00", joao)))).isInstanceOf(SharesExceedAmountException.class);
        assertThat(stored).hasSize(1);
    }

    @Test
    @DisplayName("assembleAll (lista) traz a dívida de cada movimentação de dívida e as partes de cada despesa dividida, com as flags certas")
    void deveMontarAListaComDividas() {
        TransactionDetails loan = loan("300.00", joao);
        TransactionDetails split = split("200.00", List.of(share("100.00", pedro)));
        TransactionDetails plain = create.handle(new Expense(common("10.00"), nubank.id(), food.id(), PaymentMethod.PIX), null);
        UUID loanDebt = loan.transaction().debtId();
        Debt debt = debts.findById(USER_ID, loanDebt).orElseThrow().value();
        List<Debt> all = new ArrayList<>(List.of(debt));
        debts.findByOriginTransaction(USER_ID, split.transaction().id()).forEach(v -> all.add(v.value()));
        FinancialRecords records = new FinancialRecords(List.of(nubank), List.of(), stored, all);

        List<Persisted<Transaction>> items = new ArrayList<>();
        stored.forEach(t -> items.add(new Persisted<>(t, NOW, NOW)));
        List<TransactionDetails> listed = assembler.assembleAll(USER_ID, items, records);
        assertThat(listed.get(0).debts()).extracting(DebtSummary::direction).containsExactly(DebtDirection.RECEIVABLE);
        assertThat(listed.get(0).flags().voidable()).isTrue();
        assertThat(listed.get(1).debts()).hasSize(1);
        assertThat(listed.get(1).transaction().type()).isEqualTo(TransactionType.EXPENSE);
        assertThat(listed.get(2).debts()).isEmpty();
        assertThat(plain.debts()).isEmpty();
    }

    @Test
    @DisplayName("reembolsar uma despesa dividida: o teto é a parte do próprio usuário (a parte do João não é consumo), senão o consumo da categoria ficaria negativo")
    void deveLimitarOReembolsoAParteDoUsuario() {
        TransactionDetails split = split("200.00", List.of(share("100.00", joao)));
        UUID expenseId = split.transaction().id();
        assertThatThrownBy(() -> create.handle(new CreateTransactionCommand.Refund(common("150.00"), nubank.id(), food.id(), expenseId, null), null))
                .isInstanceOf(com.moneylog.core.transaction.exception.TransactionExceptions.RefundExceedsOriginalException.class);
        TransactionDetails refund = create.handle(new CreateTransactionCommand.Refund(common("100.00"), nubank.id(), food.id(), expenseId, null), null);
        assertThat(refund.transaction().type()).isEqualTo(TransactionType.REFUND);
        // (a soma dos reembolsos anteriores vem do banco; o IT fecha o teto depois do primeiro reembolso)
        // despesa comum: o teto continua sendo o valor inteiro
        TransactionDetails plain = create.handle(new Expense(common("50.00"), nubank.id(), food.id(), PaymentMethod.PIX), null);
        create.handle(new CreateTransactionCommand.Refund(common("50.00"), nubank.id(), food.id(), plain.transaction().id(), null), null);
    }

    // ---------------------------------------------------------------- estorno

    @Test
    @DisplayName("estornar a quitação devolve o status sem gravar a dívida; estornar a origem com quitação ativa é DEBT_HAS_SETTLEMENTS e nada é gravado")
    void deveEstornarComAsGuardasDaDivida() {
        TransactionDetails origin = loan("300.00", joao);
        UUID debtId = origin.transaction().debtId();
        TransactionDetails settlement = received("100.00", debtId);

        assertThatThrownBy(() -> voidUseCase.handle(voidCommand(origin.transaction()))).isInstanceOf(TransactionVoidRefusedException.class)
                .extracting("code").isEqualTo("DEBT_HAS_SETTLEMENTS");
        verify(transactions, never()).update(any());
        assertThat(origin.transaction().status()).isEqualTo(TransactionStatus.ACTIVE);

        TransactionDetails voided = voidUseCase.handle(voidCommand(settlement.transaction()));
        assertThat(voided.transaction().status()).isEqualTo(TransactionStatus.VOIDED);
        assertThat(debts.findById(USER_ID, debtId).orElseThrow().value().status()).isEqualTo(DebtStatus.OPEN);
        verify(debts, never()).update(any(), org.mockito.ArgumentMatchers.anyLong());
        verify(ledger).deactivateBySource(eq(USER_ID), eq(PostingSourceType.TRANSACTION), eq(settlement.transaction().id()), any(), eq(NOW));
    }

    @Test
    @DisplayName("estornar a origem sem quitações: a dívida vira VOIDED e é gravada depois da movimentação; dívida perdoada recusa com DEBT_CLOSED")
    void deveEstornarAOrigem() {
        TransactionDetails origin = loan("300.00", joao);
        UUID debtId = origin.transaction().debtId();
        TransactionDetails voided = voidUseCase.handle(voidCommand(origin.transaction()));
        assertThat(voided.transaction().status()).isEqualTo(TransactionStatus.VOIDED);
        assertThat(debts.findById(USER_ID, debtId).orElseThrow().value().status()).isEqualTo(DebtStatus.VOIDED);
        assertThat(voided.debts().get(0).status()).isEqualTo(DebtStatus.VOIDED);
        assertThat(voided.flags()).isEqualTo(new Flags(false, false, null));
        InOrder order = inOrder(transactions, debts);
        order.verify(transactions).update(any(Transaction.class));
        order.verify(debts).update(any(Debt.class), org.mockito.ArgumentMatchers.anyLong());

        TransactionDetails second = loan("100.00", joao);
        debts.findById(USER_ID, second.transaction().debtId()).orElseThrow().value().cancel("perdão", TODAY);
        assertThatThrownBy(() -> voidUseCase.handle(voidCommand(second.transaction()))).isInstanceOf(TransactionVoidRefusedException.class)
                .extracting("code").isEqualTo("DEBT_CLOSED");
        // com a conta arquivada nem a movimentação de dívida se estorna (S-73)
        Account archivedAccount = Account.restore(nubank.id(), USER_ID, "Nubank", nubank.type(), "Nubank", nubank.openingBalance(),
                nubank.openingDate(), com.moneylog.core.account.domain.AccountStatus.ARCHIVED, 3L);
        TransactionDetails third = loan("50.00", pedro);
        when(accounts.findById(USER_ID, nubank.id())).thenReturn(Optional.of(new Persisted<>(archivedAccount, NOW, NOW)));
        assertThatThrownBy(() -> voidUseCase.handle(voidCommand(third.transaction()))).isInstanceOf(TransactionVoidRefusedException.class)
                .extracting("code").isEqualTo("ACCOUNT_ARCHIVED");
    }

    @Test
    @DisplayName("estornar a despesa dividida sem quitação estorna despesa e as duas dívidas; com quitação em uma delas é recusado")
    void deveEstornarADespesaDividida() {
        TransactionDetails clean = split("200.00", List.of(share("100.00", joao), share("40.00", pedro)));
        List<UUID> debtIds = clean.debts().stream().map(DebtSummary::id).toList();
        TransactionDetails voided = voidUseCase.handle(voidCommand(clean.transaction()));
        assertThat(voided.transaction().status()).isEqualTo(TransactionStatus.VOIDED);
        assertThat(debtIds).allSatisfy(id -> assertThat(debts.findById(USER_ID, id).orElseThrow().value().status()).isEqualTo(DebtStatus.VOIDED));
        verify(debts, org.mockito.Mockito.times(2)).update(any(Debt.class), org.mockito.ArgumentMatchers.anyLong());

        TransactionDetails withSettlement = split("200.00", List.of(share("100.00", joao)));
        received("10.00", withSettlement.debts().get(0).id());
        assertThatThrownBy(() -> voidUseCase.handle(voidCommand(withSettlement.transaction()))).isInstanceOf(DebtOperationRefusedException.class)
                .extracting("code").isEqualTo("DEBT_HAS_SETTLEMENTS");
        assertThat(withSettlement.transaction().status()).isEqualTo(TransactionStatus.ACTIVE);
    }

    // ---------------------------------------------------------------- edição

    @Test
    @DisplayName("movimentação de dívida não é editável; despesa dividida só muda descrição, categoria e observação (T78 CA7)")
    void deveLimitarAEdicao() {
        TransactionDetails loan = loan("300.00", joao);
        assertThatThrownBy(() -> update.handle(new UpdateTransactionCommand(loan.transaction().id(), 0, new LoanGiven(common("1.00"), nubank.id(),
                new NewDebtSpec(joao.id(), null, null), null)))).isInstanceOf(TransactionNotEditableException.class);

        TransactionDetails split = split("200.00", List.of(share("100.00", joao)));
        Transaction current = split.transaction();
        Common edited = new Common(USER_ID, current.amount(), current.date(), "Jantar com amigos", "obs");
        TransactionDetails ok = update.handle(new UpdateTransactionCommand(current.id(), current.version(),
                new Expense(edited, nubank.id(), food.id(), PaymentMethod.PIX)));
        assertThat(ok.transaction().description()).isEqualTo("Jantar com amigos");
        assertThat(ok.transaction().notes()).isEqualTo("obs");
        assertThat(ok.debts()).hasSize(1);
        assertThat(ok.impact().consumption().get(0).amount()).isEqualTo(Money.of("100.00"));

        Common higher = new Common(USER_ID, Money.of("300.00"), current.date(), "x", null);
        assertThatThrownBy(() -> update.handle(new UpdateTransactionCommand(current.id(), current.version(),
                new Expense(higher, nubank.id(), food.id(), PaymentMethod.PIX)))).isInstanceOf(TransactionNotEditableException.class);
        assertThatThrownBy(() -> update.handle(new UpdateTransactionCommand(current.id(), current.version(),
                new Expense(edited, nubank.id(), food.id(), PaymentMethod.DEBIT)))).isInstanceOf(TransactionNotEditableException.class);
        assertThatThrownBy(() -> update.handle(new UpdateTransactionCommand(current.id(), current.version(),
                new Expense(edited, nubank.id(), food.id(), PaymentMethod.PIX, List.of(share("10.00", pedro))))))
                .isInstanceOf(TransactionNotEditableException.class);

        Transaction plain = create.handle(new Expense(common("10.00"), nubank.id(), food.id(), PaymentMethod.PIX), null).transaction();
        assertThatThrownBy(() -> update.handle(new UpdateTransactionCommand(plain.id(), plain.version(),
                new Expense(common("10.00"), nubank.id(), food.id(), PaymentMethod.PIX, List.of(share("5.00", pedro))))))
                .isInstanceOf(TransactionNotEditableException.class);
    }

    @Test
    @DisplayName("a idempotência considera as partes: o mesmo corpo repete o hash, partes diferentes mudam")
    void deveIncluirAsPartesNoHash() {
        Expense plain = new Expense(common("200.00"), nubank.id(), food.id(), PaymentMethod.PIX);
        Expense withShares = new Expense(common("200.00"), nubank.id(), food.id(), PaymentMethod.PIX, List.of(share("100.00", joao)));
        Expense otherShare = new Expense(common("200.00"), nubank.id(), food.id(), PaymentMethod.PIX, List.of(share("90.00", joao)));
        assertThat(CanonicalCommandHash.of(withShares)).isEqualTo(CanonicalCommandHash.of(
                new Expense(common("200.00"), nubank.id(), food.id(), PaymentMethod.PIX, List.of(share("100.00", joao)))));
        assertThat(CanonicalCommandHash.of(withShares)).isNotEqualTo(CanonicalCommandHash.of(plain)).isNotEqualTo(CanonicalCommandHash.of(otherShare));
        CreateTransactionCommand loan = new LoanGiven(common("1.00"), nubank.id(), new NewDebtSpec(joao.id(), null, "x"), null);
        assertThat(CanonicalCommandHash.of(loan)).isNotEqualTo(CanonicalCommandHash.of(new LoanGiven(common("1.00"), nubank.id(),
                new NewDebtSpec(joao.id(), LocalDate.of(2026, 11, 5), "x"), null)));
        assertThat(CanonicalCommandHash.of(new DebtPayment(common("1.00"), nubank.id(), UUID.randomUUID()))).hasSize(64);
        assertThat(CanonicalCommandHash.of(new MoneyReceived(common("1.00"), nubank.id(), UUID.randomUUID(), null))).hasSize(64);
    }
}
