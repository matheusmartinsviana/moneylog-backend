package com.moneylog.core.recurrence.app.usecase;

import static com.moneylog.core.account.util.factory.AccountTestFactory.NUBANK_ID;
import static com.moneylog.core.util.Ids.OTHER_USER_ID;
import static com.moneylog.core.util.Ids.USER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.mock;
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

import com.moneylog.core.account.app.gateways.AccountGateway;
import com.moneylog.core.account.domain.Account;
import com.moneylog.core.account.domain.AccountStatus;
import com.moneylog.core.account.exception.AccountExceptions.AccountArchivedException;
import com.moneylog.core.account.exception.AccountExceptions.AccountNotFoundException;
import com.moneylog.core.account.util.factory.AccountTestFactory;
import com.moneylog.core.card.app.gateways.CardGateway;
import com.moneylog.core.card.domain.CreditCard;
import com.moneylog.core.card.exception.CardExceptions.CardArchivedException;
import com.moneylog.core.card.util.factory.CreditCardTestFactory;
import com.moneylog.core.category.app.gateways.CategoryGateway;
import com.moneylog.core.category.domain.Category;
import com.moneylog.core.category.domain.CategoryKind;
import com.moneylog.core.category.exception.CategoryExceptions.CategoryKindMismatchException;
import com.moneylog.core.category.exception.CategoryExceptions.CategoryNotFoundException;
import com.moneylog.core.category.util.factory.CategoryTestFactory;
import com.moneylog.core.recurrence.app.OccurrenceView;
import com.moneylog.core.recurrence.app.RecurrenceView;
import com.moneylog.core.recurrence.app.RecurrenceViews;
import com.moneylog.core.recurrence.app.usecase.CreateRecurrenceUseCase.CreateRecurrenceCommand;
import com.moneylog.core.recurrence.app.usecase.DeactivateRecurrenceUseCase.DeactivateRecurrenceCommand;
import com.moneylog.core.recurrence.app.usecase.EndRecurrenceUseCase.EndRecurrenceCommand;
import com.moneylog.core.recurrence.app.usecase.GetRecurrenceSummaryUseCase.GetRecurrenceSummaryQuery;
import com.moneylog.core.recurrence.app.usecase.GetRecurrenceUseCase.GetRecurrenceQuery;
import com.moneylog.core.recurrence.app.usecase.ListOccurrencesUseCase.ListOccurrencesQuery;
import com.moneylog.core.recurrence.app.usecase.ListRecurrencesUseCase.ListRecurrencesQuery;
import com.moneylog.core.recurrence.app.usecase.UpdateRecurrenceUseCase.UpdateRecurrenceCommand;
import com.moneylog.core.recurrence.domain.Frequency;
import com.moneylog.core.recurrence.domain.IntervalUnit;
import com.moneylog.core.recurrence.domain.OccurrenceStatus;
import com.moneylog.core.recurrence.domain.RecurringTransaction;
import com.moneylog.core.recurrence.exception.RecurrenceExceptions.InvalidRecurrenceException;
import com.moneylog.core.recurrence.exception.RecurrenceExceptions.RecurrenceEndedException;
import com.moneylog.core.recurrence.exception.RecurrenceExceptions.RecurrenceNotFoundException;
import com.moneylog.core.recurrence.util.InMemoryRecurrenceGateway;
import com.moneylog.core.sk.exception.VersionConflictException;
import com.moneylog.core.sk.id.IdGenerator;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.page.PageQuery;
import com.moneylog.core.sk.persistence.Persisted;
import com.moneylog.core.sk.time.FinancialDates.DateOutOfRangeException;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.core.transaction.domain.PaymentMethod;
import com.moneylog.core.transaction.domain.Transaction;
import com.moneylog.core.transaction.domain.TransactionType;
import com.moneylog.core.transaction.exception.TransactionExceptions.AccountNotCashException;

@DisplayName("Recorrências: casos de uso (T79)")
class RecorrenciasUseCasesTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 10);
    private static final Instant NOW = Instant.parse("2026-10-10T15:00:00Z");

    private final List<Transaction> transactions = new ArrayList<>();
    private final InMemoryRecurrenceGateway recurrences = new InMemoryRecurrenceGateway(() -> transactions);
    private final AccountGateway accounts = mock(AccountGateway.class);
    private final CardGateway cards = mock(CardGateway.class);
    private final CategoryGateway categories = mock(CategoryGateway.class);
    private final TimeProvider time = mock(TimeProvider.class);
    private final com.moneylog.core.transaction.app.gateways.IdempotencyGateway idempotency = mock(com.moneylog.core.transaction.app.gateways.IdempotencyGateway.class);
    private final Account nubank = AccountTestFactory.umaContaCorrente();
    private final CreditCard roxinho = CreditCardTestFactory.umCartao();
    private final Category bills = CategoryTestFactory.umaCategoriaDeDespesa();
    private final Category salary = CategoryTestFactory.umaCategoriaDeReceita();
    private int sequence = 1;
    private final IdGenerator ids = () -> UUID.fromString(String.format("00000000-0000-0000-0000-0000000c%04d", sequence++));

    private CreateRecurrenceUseCaseImpl create;
    private UpdateRecurrenceUseCaseImpl update;
    private EndRecurrenceUseCaseImpl end;
    private DeactivateRecurrenceUseCaseImpl deactivate;
    private GetRecurrenceUseCaseImpl get;
    private ListRecurrencesUseCaseImpl list;
    private ListOccurrencesUseCaseImpl occurrences;
    private GetRecurrenceSummaryUseCaseImpl summary;

    @BeforeEach
    void setUp() {
        when(time.today()).thenReturn(TODAY);
        when(time.now()).thenReturn(NOW);
        when(accounts.findById(USER_ID, nubank.id())).thenReturn(Optional.of(new Persisted<>(nubank, NOW, NOW)));
        when(cards.findById(USER_ID, roxinho.id())).thenReturn(Optional.of(new Persisted<>(roxinho, NOW, NOW)));
        when(categories.findById(USER_ID, bills.id())).thenReturn(Optional.of(bills));
        when(categories.findById(USER_ID, salary.id())).thenReturn(Optional.of(salary));
        when(categories.list(USER_ID, null, true)).thenReturn(List.of(bills, salary));
        RecurrenceViews views = new RecurrenceViews(categories, accounts, cards);
        create = new CreateRecurrenceUseCaseImpl(recurrences, categories, accounts, cards, idempotency, ids, time);
        update = new UpdateRecurrenceUseCaseImpl(recurrences, categories, accounts, cards, time);
        end = new EndRecurrenceUseCaseImpl(recurrences, views, time);
        deactivate = new DeactivateRecurrenceUseCaseImpl(recurrences, views, time);
        get = new GetRecurrenceUseCaseImpl(recurrences, views, time);
        list = new ListRecurrencesUseCaseImpl(recurrences, views, time);
        occurrences = new ListOccurrencesUseCaseImpl(recurrences, time);
        summary = new GetRecurrenceSummaryUseCaseImpl(recurrences, time);
    }

    private CreateRecurrenceCommand internet(LocalDate start) {
        return new CreateRecurrenceCommand(USER_ID, TransactionType.EXPENSE, Money.of("119.00"), "Internet", bills.id(), nubank.id(), PaymentMethod.PIX,
                null, Frequency.MONTHLY, null, null, start, null, null);
    }

    private CreateRecurrenceCommand subscription() {
        return new CreateRecurrenceCommand(USER_ID, TransactionType.CARD_PURCHASE, Money.of("49.90"), "Streaming", bills.id(), null, null, roxinho.id(),
                Frequency.MONTHLY, null, null, LocalDate.of(2026, 9, 15), null, null);
    }

    @Test
    @DisplayName("criar mensal de 119,00 com início 10/09: versão 0, estimativa mensal 119,00, próxima pendente é a vencida de 10/09; cartão à vista aceito")
    void deveCriar() {
        RecurrenceView created = create.handle(internet(LocalDate.of(2026, 9, 10)));
        assertThat(created.stored().version()).isZero();
        assertThat(created.monthlyEstimate()).isEqualTo(Money.of("119.00"));
        assertThat(created.nextOccurrence()).isEqualTo(LocalDate.of(2026, 9, 10));
        assertThat(created.category().name()).isEqualTo(bills.name());
        assertThat(created.account().id()).isEqualTo(nubank.id());
        assertThat(created.card()).isNull();
        RecurrenceView card = create.handle(subscription());
        assertThat(card.card().id()).isEqualTo(roxinho.id());
        assertThat(card.account()).isNull();
        assertThat(card.recurrence().type()).isEqualTo(TransactionType.CARD_PURCHASE);
        RecurrenceView custom = create.handle(new CreateRecurrenceCommand(USER_ID, TransactionType.INCOME, Money.of("5000.00"), "Salário", salary.id(),
                nubank.id(), PaymentMethod.TRANSFER, null, Frequency.CUSTOM, 2, IntervalUnit.WEEKS, LocalDate.of(2026, 10, 5), null, 10));
        assertThat(custom.recurrence().schedule().interval()).isEqualTo(2);
        assertThat(custom.monthlyEstimate()).isEqualTo(Money.of("10833.33"));
    }

    @Test
    @DisplayName("sem descrição, categoria e método: descrição nula, categoria de sistema padrão e OTHER; sem categoria padrão = 422")
    void deveResolverPadroesNaCriacao() {
        Category otherExpense = Category.createSystem(UUID.fromString("00000000-0000-0000-0000-00000000c0a1"), USER_ID, "Outros", CategoryKind.EXPENSE);
        Category otherIncome = Category.createSystem(UUID.fromString("00000000-0000-0000-0000-00000000c0a2"), USER_ID, "Outras receitas", CategoryKind.INCOME);
        when(categories.findBySystemKey(USER_ID, "OTHER_EXPENSE")).thenReturn(Optional.of(otherExpense));
        when(categories.findBySystemKey(USER_ID, "OTHER_INCOME")).thenReturn(Optional.of(otherIncome));
        when(categories.findById(USER_ID, otherExpense.id())).thenReturn(Optional.of(otherExpense));
        when(categories.findById(USER_ID, otherIncome.id())).thenReturn(Optional.of(otherIncome));
        when(categories.list(USER_ID, null, true)).thenReturn(List.of(bills, salary, otherExpense, otherIncome));

        RecurrenceView expense = create.handle(new CreateRecurrenceCommand(USER_ID, TransactionType.EXPENSE, Money.of("30.00"), null, null,
                nubank.id(), null, null, Frequency.MONTHLY, null, null, TODAY, null, null));
        RecurrenceView income = create.handle(new CreateRecurrenceCommand(USER_ID, TransactionType.INCOME, Money.of("30.00"), null, null,
                nubank.id(), null, null, Frequency.MONTHLY, null, null, TODAY, null, null));
        RecurrenceView card = create.handle(new CreateRecurrenceCommand(USER_ID, TransactionType.CARD_PURCHASE, Money.of("30.00"), null, null,
                null, null, roxinho.id(), Frequency.MONTHLY, null, null, TODAY, null, null));

        assertThat(expense.recurrence().description()).isNull();
        assertThat(expense.recurrence().categoryId()).isEqualTo(otherExpense.id());
        assertThat(expense.recurrence().paymentMethod()).isEqualTo(PaymentMethod.OTHER);
        assertThat(income.recurrence().categoryId()).isEqualTo(otherIncome.id());
        assertThat(card.recurrence().categoryId()).isEqualTo(otherExpense.id());
        assertThat(card.recurrence().paymentMethod()).isNull();

        when(categories.findBySystemKey(USER_ID, "OTHER_EXPENSE")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> create.handle(new CreateRecurrenceCommand(USER_ID, TransactionType.EXPENSE, Money.of("30.00"), null, null,
                nubank.id(), null, null, Frequency.MONTHLY, null, null, TODAY, null, null)))
                .hasFieldOrPropertyWithValue("code", "DEFAULT_CATEGORY_UNAVAILABLE");
    }

    @Test
    @DisplayName("criar recusa conta e cartão juntos, tipo trocado, categoria de tipo errado/inexistente, conta arquivada ou que não é CASH, cartão arquivado, data fora da janela")
    void deveRecusarNaCriacao() {
        assertThatThrownBy(() -> create.handle(new CreateRecurrenceCommand(USER_ID, TransactionType.EXPENSE, Money.of("1.00"), "x", bills.id(),
                nubank.id(), PaymentMethod.PIX, roxinho.id(), Frequency.MONTHLY, null, null, TODAY, null, null))).isInstanceOf(InvalidRecurrenceException.class);
        assertThatThrownBy(() -> create.handle(new CreateRecurrenceCommand(USER_ID, TransactionType.CARD_PURCHASE, Money.of("1.00"), "x", bills.id(),
                nubank.id(), null, roxinho.id(), Frequency.MONTHLY, null, null, TODAY, null, null))).isInstanceOf(InvalidRecurrenceException.class);
        assertThatThrownBy(() -> create.handle(new CreateRecurrenceCommand(USER_ID, TransactionType.CARD_PURCHASE, Money.of("1.00"), "x", bills.id(),
                nubank.id(), null, null, Frequency.MONTHLY, null, null, TODAY, null, null))).isInstanceOf(InvalidRecurrenceException.class);
        assertThatThrownBy(() -> create.handle(new CreateRecurrenceCommand(USER_ID, TransactionType.EXPENSE, Money.of("1.00"), "x", bills.id(),
                null, null, roxinho.id(), Frequency.MONTHLY, null, null, TODAY, null, null))).isInstanceOf(InvalidRecurrenceException.class);
        assertThatThrownBy(() -> create.handle(new CreateRecurrenceCommand(USER_ID, TransactionType.INCOME, Money.of("1.00"), "x", bills.id(),
                nubank.id(), PaymentMethod.PIX, null, Frequency.MONTHLY, null, null, TODAY, null, null))).isInstanceOf(CategoryKindMismatchException.class);
        assertThatThrownBy(() -> create.handle(new CreateRecurrenceCommand(USER_ID, TransactionType.EXPENSE, Money.of("1.00"), "x", UUID.randomUUID(),
                nubank.id(), PaymentMethod.PIX, null, Frequency.MONTHLY, null, null, TODAY, null, null))).isInstanceOf(CategoryNotFoundException.class);
        assertThatThrownBy(() -> create.handle(new CreateRecurrenceCommand(USER_ID, TransactionType.EXPENSE, Money.of("1.00"), "x", bills.id(),
                UUID.randomUUID(), PaymentMethod.PIX, null, Frequency.MONTHLY, null, null, TODAY, null, null))).isInstanceOf(AccountNotFoundException.class);
        Account archived = Account.restore(NUBANK_ID, USER_ID, "Nubank", nubank.type(), "Nubank", nubank.openingBalance(), nubank.openingDate(),
                AccountStatus.ARCHIVED, 3L);
        when(accounts.findById(USER_ID, nubank.id())).thenReturn(Optional.of(new Persisted<>(archived, NOW, NOW)));
        assertThatThrownBy(() -> create.handle(internet(TODAY))).isInstanceOf(AccountArchivedException.class);
        Account investment = AccountTestFactory.umaContaDeInvestimento();
        when(accounts.findById(USER_ID, investment.id())).thenReturn(Optional.of(new Persisted<>(investment, NOW, NOW)));
        assertThatThrownBy(() -> create.handle(new CreateRecurrenceCommand(USER_ID, TransactionType.EXPENSE, Money.of("1.00"), "x", bills.id(),
                investment.id(), PaymentMethod.PIX, null, Frequency.MONTHLY, null, null, TODAY, null, null))).isInstanceOf(AccountNotCashException.class);
        when(cards.findById(USER_ID, roxinho.id())).thenReturn(Optional.of(new Persisted<>(CreditCardTestFactory.umCartaoArquivado(), NOW, NOW)));
        assertThatThrownBy(() -> create.handle(subscription())).isInstanceOf(CardArchivedException.class);
        assertThatThrownBy(() -> create.handle(internet(LocalDate.of(1990, 1, 1)))).isInstanceOf(DateOutOfRangeException.class);
        assertThat(recurrences.findAll(USER_ID)).isEmpty();
    }

    @Test
    @DisplayName("editar: só o modelo (valor, descrição, categoria, destino, fim), com a versão; só o que mudou é revalidado; o fim respeita o que já foi realizado")
    void deveEditar() {
        RecurrenceView created = create.handle(internet(LocalDate.of(2026, 10, 10)));
        UUID id = created.recurrence().id();
        RecurrenceView edited = update.handle(new UpdateRecurrenceCommand(USER_ID, id, Money.of("129.90"), "Internet fibra", bills.id(), nubank.id(),
                PaymentMethod.PIX, null, LocalDate.of(2026, 12, 31), 0));
        assertThat(edited.recurrence().amount()).isEqualTo(Money.of("129.90"));
        assertThat(edited.recurrence().schedule().endDate()).isEqualTo(LocalDate.of(2026, 12, 31));
        assertThat(edited.stored().version()).isEqualTo(1);
        assertThat(edited.monthlyEstimate()).isEqualTo(Money.of("129.90"));
        Account archived = Account.restore(NUBANK_ID, USER_ID, "Nubank", nubank.type(), "Nubank", nubank.openingBalance(), nubank.openingDate(),
                AccountStatus.ARCHIVED, 3L);
        when(accounts.findById(USER_ID, nubank.id())).thenReturn(Optional.of(new Persisted<>(archived, NOW, NOW)));
        update.handle(new UpdateRecurrenceCommand(USER_ID, id, Money.of("130.00"), "Internet fibra", bills.id(), nubank.id(), PaymentMethod.PIX, null, null, 1));

        assertThatThrownBy(() -> update.handle(new UpdateRecurrenceCommand(USER_ID, id, Money.of("1.00"), "x", bills.id(), nubank.id(), PaymentMethod.PIX,
                null, null, 0))).isInstanceOf(VersionConflictException.class);
        assertThatThrownBy(() -> update.handle(new UpdateRecurrenceCommand(OTHER_USER_ID, id, Money.of("1.00"), "x", bills.id(), nubank.id(),
                PaymentMethod.PIX, null, null, 2))).isInstanceOf(RecurrenceNotFoundException.class);
        assertThatThrownBy(() -> update.handle(new UpdateRecurrenceCommand(USER_ID, id, Money.of("1.00"), "x", bills.id(), nubank.id(), PaymentMethod.PIX,
                roxinho.id(), null, 2))).isInstanceOf(InvalidRecurrenceException.class);
        assertThatThrownBy(() -> update.handle(new UpdateRecurrenceCommand(USER_ID, id, Money.of("1.00"), "x", bills.id(), UUID.randomUUID(),
                PaymentMethod.PIX, null, null, 2))).isInstanceOf(AccountNotFoundException.class);
        assertThatThrownBy(() -> update.handle(new UpdateRecurrenceCommand(USER_ID, id, Money.of("1.00"), "x", UUID.randomUUID(), nubank.id(),
                PaymentMethod.PIX, null, null, 2))).isInstanceOf(CategoryNotFoundException.class);
        RecurrenceView card = create.handle(subscription());
        UUID cardRecurrence = card.recurrence().id();
        assertThatThrownBy(() -> update.handle(new UpdateRecurrenceCommand(USER_ID, cardRecurrence, Money.of("1.00"), "x", bills.id(), null, null,
                UUID.randomUUID(), null, 0))).isInstanceOf(com.moneylog.core.card.exception.CardExceptions.CardNotFoundException.class);
    }

    @Test
    @DisplayName("encerrar: sem data usa hoje; deve respeitar o início; desativar é idempotente e zera a estimativa; a próxima ocorrência some")
    void deveEncerrarEDesativar() {
        RecurrenceView created = create.handle(internet(LocalDate.of(2026, 9, 10)));
        UUID id = created.recurrence().id();
        RecurrenceView ended = end.handle(new EndRecurrenceCommand(USER_ID, id, null));
        assertThat(ended.recurrence().schedule().endDate()).isEqualTo(TODAY);
        assertThatThrownBy(() -> end.handle(new EndRecurrenceCommand(USER_ID, id, LocalDate.of(2026, 8, 1)))).isInstanceOf(InvalidRecurrenceException.class);
        assertThatThrownBy(() -> end.handle(new EndRecurrenceCommand(USER_ID, UUID.randomUUID(), null))).isInstanceOf(RecurrenceNotFoundException.class);
        assertThatThrownBy(() -> end.handle(new EndRecurrenceCommand(USER_ID, id, LocalDate.of(1990, 1, 1)))).isInstanceOf(DateOutOfRangeException.class);

        RecurrenceView off = deactivate.handle(new DeactivateRecurrenceCommand(USER_ID, id));
        assertThat(off.recurrence().isActive()).isFalse();
        assertThat(off.monthlyEstimate()).isEqualTo(Money.of("0.00"));
        assertThat(off.nextOccurrence()).isNull();
        long version = off.stored().version();
        assertThat(deactivate.handle(new DeactivateRecurrenceCommand(USER_ID, id)).stored().version()).isEqualTo(version);
        assertThatThrownBy(() -> deactivate.handle(new DeactivateRecurrenceCommand(OTHER_USER_ID, id))).isInstanceOf(RecurrenceNotFoundException.class);
    }

    @Test
    @DisplayName("ler e listar: ordem estável, filtro de ativas, paginação sem COUNT e outro usuário não vê")
    void deveLerEListar() {
        RecurrenceView first = create.handle(internet(LocalDate.of(2026, 10, 10)));
        create.handle(subscription());
        deactivate.handle(new DeactivateRecurrenceCommand(USER_ID, first.recurrence().id()));
        assertThat(get.handle(new GetRecurrenceQuery(USER_ID, first.recurrence().id())).recurrence().isActive()).isFalse();
        assertThatThrownBy(() -> get.handle(new GetRecurrenceQuery(OTHER_USER_ID, first.recurrence().id()))).isInstanceOf(RecurrenceNotFoundException.class);
        assertThat(list.handle(new ListRecurrencesQuery(USER_ID, null, PageQuery.first())).items()).hasSize(2);
        assertThat(list.handle(new ListRecurrencesQuery(USER_ID, true, PageQuery.first())).items()).hasSize(1);
        assertThat(list.handle(new ListRecurrencesQuery(USER_ID, false, PageQuery.first())).items()).hasSize(1);
        var page = list.handle(new ListRecurrencesQuery(USER_ID, null, new PageQuery(1, 1)));
        assertThat(page.items()).hasSize(1);
        assertThat(page.hasNext()).isTrue();
        assertThat(list.handle(new ListRecurrencesQuery(OTHER_USER_ID, null, PageQuery.first())).items()).isEmpty();
    }

    @Test
    @DisplayName("ocorrências: pendentes (inclusive vencidas sem from), realizadas e puladas em [from, to], ordem por data; overdue pelo relógio")
    void deveListarOcorrencias() {
        RecurrenceView created = create.handle(internet(LocalDate.of(2026, 9, 10)));
        UUID id = created.recurrence().id();
        RecurringTransaction model = recurrences.findById(USER_ID, id).orElseThrow();
        model.skip(LocalDate.of(2026, 11, 10));
        recurrences.save(model);

        var all = occurrences.handle(new ListOccurrencesQuery(USER_ID, null, LocalDate.of(2026, 12, 31), null, PageQuery.first()));
        assertThat(all.items()).extracting(OccurrenceView::date).containsExactly(LocalDate.of(2026, 9, 10), LocalDate.of(2026, 10, 10),
                LocalDate.of(2026, 11, 10), LocalDate.of(2026, 12, 10));
        assertThat(all.items()).extracting(OccurrenceView::status).containsExactly(OccurrenceStatus.PENDING, OccurrenceStatus.PENDING,
                OccurrenceStatus.SKIPPED, OccurrenceStatus.PENDING);
        assertThat(all.items().get(0).overdue()).isTrue();
        assertThat(all.items().get(1).overdue()).isFalse();
        var window = occurrences.handle(new ListOccurrencesQuery(USER_ID, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 12, 31),
                OccurrenceStatus.PENDING, PageQuery.first()));
        assertThat(window.items()).extracting(OccurrenceView::date).containsExactly(LocalDate.of(2026, 10, 10), LocalDate.of(2026, 12, 10));
        var skipped = occurrences.handle(new ListOccurrencesQuery(USER_ID, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 12, 31), OccurrenceStatus.SKIPPED, PageQuery.first()));
        assertThat(skipped.items()).hasSize(1);
        var defaults = occurrences.handle(new ListOccurrencesQuery(USER_ID, LocalDate.of(2026, 10, 1), null, OccurrenceStatus.PENDING, PageQuery.first()));
        assertThat(defaults.items()).extracting(OccurrenceView::date).containsExactly(LocalDate.of(2026, 10, 10));
        var firstPage = occurrences.handle(new ListOccurrencesQuery(USER_ID, null, LocalDate.of(2026, 12, 31), null, new PageQuery(1, 2)));
        assertThat(firstPage.items()).hasSize(2);
        assertThat(firstPage.hasNext()).isTrue();
        assertThatThrownBy(() -> occurrences.handle(new ListOccurrencesQuery(USER_ID, LocalDate.of(2026, 12, 1), LocalDate.of(2026, 10, 1), null, PageQuery.first())))
                .isInstanceOf(IllegalArgumentException.class);
        Transaction realized = model.realize(LocalDate.of(2026, 10, 10), UUID.fromString("00000000-0000-0000-0000-0000000e1001"), Money.of("124.90"),
                nubank, bills);
        transactions.add(realized);
        var done = occurrences.handle(new ListOccurrencesQuery(USER_ID, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), OccurrenceStatus.REALIZED,
                PageQuery.first()));
        assertThat(done.items()).hasSize(1);
        assertThat(done.items().get(0).amount()).isEqualTo(Money.of("124.90"));
        assertThat(done.items().get(0).transactionId()).isEqualTo(realized.id());
        assertThat(occurrences.handle(new ListOccurrencesQuery(OTHER_USER_ID, null, null, null, PageQuery.first())).items()).isEmpty();
    }

    @Test
    @DisplayName("custo fixo e receita recorrente mensais (estimativa): semanal × 52/12; desativada não entra")
    void deveResumirOCustoFixo() {
        create.handle(internet(LocalDate.of(2026, 10, 10)));
        RecurrenceView weekly = create.handle(new CreateRecurrenceCommand(USER_ID, TransactionType.EXPENSE, Money.of("100.00"), "Diarista", bills.id(),
                nubank.id(), PaymentMethod.PIX, null, Frequency.WEEKLY, null, null, LocalDate.of(2026, 10, 12), null, null));
        create.handle(new CreateRecurrenceCommand(USER_ID, TransactionType.INCOME, Money.of("5000.00"), "Salário", salary.id(), nubank.id(),
                PaymentMethod.TRANSFER, null, Frequency.MONTHLY, null, null, LocalDate.of(2026, 10, 5), null, null));
        var estimate = summary.handle(new GetRecurrenceSummaryQuery(USER_ID));
        assertThat(estimate.fixedCosts()).isEqualTo(Money.of("552.33"));
        assertThat(estimate.recurringIncome()).isEqualTo(Money.of("5000.00"));
        assertThat(estimate.estimate()).isTrue();
        deactivate.handle(new DeactivateRecurrenceCommand(USER_ID, weekly.recurrence().id()));
        assertThat(summary.handle(new GetRecurrenceSummaryQuery(USER_ID)).fixedCosts()).isEqualTo(Money.of("119.00"));
        assertThat(summary.handle(new GetRecurrenceSummaryQuery(OTHER_USER_ID)).fixedCosts()).isEqualTo(Money.of("0.00"));
    }

    @Test
    @DisplayName("S-235: o item traz ended (derivado) e monthlyEstimate 0,00 quando encerrada; o resumo não a soma; a de hoje ainda conta")
    void deveRefletirOEncerramentoNoItemENoResumo() {
        RecurrenceView running = create.handle(internet(LocalDate.of(2026, 1, 10)));
        assertThat(running.ended()).isFalse();
        assertThat(running.monthlyEstimate()).isEqualTo(Money.of("119.00"));
        RecurrenceView ended = end.handle(new EndRecurrenceCommand(USER_ID, running.recurrence().id(), LocalDate.of(2026, 3, 10)));
        assertThat(ended.ended()).isTrue();
        assertThat(ended.monthlyEstimate()).isEqualTo(Money.of("0.00"));
        assertThat(ended.recurrence().isActive()).as("encerrar não desativa").isTrue();
        assertThat(ended.nextOccurrence()).isEqualTo(LocalDate.of(2026, 1, 10));
        assertThat(get.handle(new GetRecurrenceQuery(USER_ID, running.recurrence().id())).ended()).isTrue();
        assertThat(summary.handle(new GetRecurrenceSummaryQuery(USER_ID)).fixedCosts()).isEqualTo(Money.of("0.00"));
        RecurrenceView today = create.handle(internet(LocalDate.of(2026, 1, 10)));
        assertThat(end.handle(new EndRecurrenceCommand(USER_ID, today.recurrence().id(), TODAY)).ended()).isFalse();
        assertThat(summary.handle(new GetRecurrenceSummaryQuery(USER_ID)).fixedCosts()).isEqualTo(Money.of("119.00"));
    }

    @Test
    @DisplayName("S-236: PUT sem endDate numa recorrência encerrada é RECURRENCE_ENDED e nada muda; com um endDate novo ou numa em curso, segue válido")
    void naoDeveReabrirEncerradaPeloPut() {
        RecurrenceView created = create.handle(internet(LocalDate.of(2026, 1, 10)));
        UUID id = created.recurrence().id();
        RecurrenceView ended = end.handle(new EndRecurrenceCommand(USER_ID, id, LocalDate.of(2026, 3, 10)));
        long version = ended.stored().version();
        assertThatThrownBy(() -> update.handle(new UpdateRecurrenceCommand(USER_ID, id, Money.of("130.00"), "Internet", bills.id(), nubank.id(), PaymentMethod.PIX,
                null, null, version))).isInstanceOf(RecurrenceEndedException.class);
        RecurringTransaction stored = recurrences.findById(USER_ID, id).orElseThrow();
        assertThat(stored.schedule().endDate()).isEqualTo(LocalDate.of(2026, 3, 10));
        assertThat(stored.amount()).isEqualTo(Money.of("119.00"));
        assertThat(recurrences.findVersioned(USER_ID, id).orElseThrow().version()).isEqualTo(version);
        RecurrenceView reopened = update.handle(new UpdateRecurrenceCommand(USER_ID, id, Money.of("130.00"), "Internet", bills.id(), nubank.id(), PaymentMethod.PIX,
                null, LocalDate.of(2026, 12, 10), version));
        assertThat(reopened.ended()).isFalse();
        RecurrenceView removed = update.handle(new UpdateRecurrenceCommand(USER_ID, id, Money.of("130.00"), "Internet", bills.id(), nubank.id(), PaymentMethod.PIX,
                null, null, reopened.stored().version()));
        assertThat(removed.recurrence().schedule().endDate()).isNull();
        assertThat(removed.ended()).isFalse();
    }

    @Test
    @DisplayName("S-242: frequência CUSTOM sem interval (nulo) é RECURRENCE_INVALID, não assume 1 em silêncio")
    void deveExigirOIntervaloEmCustom() {
        assertThatThrownBy(() -> create.handle(new CreateRecurrenceCommand(USER_ID, TransactionType.EXPENSE, Money.of("10.00"), "x", bills.id(), nubank.id(),
                PaymentMethod.PIX, null, Frequency.CUSTOM, null, IntervalUnit.WEEKS, TODAY, null, null))).isInstanceOf(InvalidRecurrenceException.class);
        assertThat(create.handle(new CreateRecurrenceCommand(USER_ID, TransactionType.EXPENSE, Money.of("10.00"), "x", bills.id(), nubank.id(),
                PaymentMethod.PIX, null, Frequency.CUSTOM, 2, IntervalUnit.WEEKS, TODAY, null, null)).recurrence().schedule().interval()).isEqualTo(2);
    }

    @Test
    @DisplayName("S-240: as pendentes vencidas vêm limitadas às 500 mais recentes (overdueTruncated); realizadas, puladas e futuras não; em 500 exatas não trunca")
    void deveLimitarAsVencidasPendentes() {
        RecurrenceView daily = create.handle(new CreateRecurrenceCommand(USER_ID, TransactionType.EXPENSE, Money.of("1.00"), "Diária", bills.id(), nubank.id(),
                PaymentMethod.PIX, null, Frequency.DAILY, null, null, LocalDate.of(2025, 1, 1), null, null));
        long overdue = LocalDate.of(2025, 1, 1).until(TODAY, java.time.temporal.ChronoUnit.DAYS);
        assertThat(overdue).isGreaterThan(500);
        var listing = occurrences.handle(new ListOccurrencesQuery(USER_ID, null, TODAY.plusDays(5), OccurrenceStatus.PENDING, new PageQuery(1, 50)));
        assertThat(listing.overdueTruncated()).isTrue();
        assertThat(listing.items()).hasSize(50);
        java.util.List<OccurrenceView> all = new ArrayList<>();
        for (int page = 1; page < 40; page++) {
            var slice = occurrences.handle(new ListOccurrencesQuery(USER_ID, null, TODAY.plusDays(5), OccurrenceStatus.PENDING, new PageQuery(page, 50)));
            all.addAll(slice.items());
            if (!slice.hasNext()) {
                break;
            }
        }
        assertThat(all).hasSize(506);
        assertThat(all.stream().filter(OccurrenceView::overdue)).hasSize(500);
        assertThat(all.get(0).date()).isEqualTo(TODAY.minusDays(500)).as("a mais antiga mantida: as 500 mais recentes");
        assertThat(all).isSortedAccordingTo(java.util.Comparator.comparing(OccurrenceView::date));
        var window = occurrences.handle(new ListOccurrencesQuery(USER_ID, TODAY.minusDays(499), TODAY, OccurrenceStatus.PENDING, new PageQuery(1, 50)));
        assertThat(window.overdueTruncated()).isFalse();
        var exactly = occurrences.handle(new ListOccurrencesQuery(USER_ID, TODAY.minusDays(500), TODAY, OccurrenceStatus.PENDING, new PageQuery(1, 50)));
        assertThat(exactly.overdueTruncated()).isFalse();
        var oneMore = occurrences.handle(new ListOccurrencesQuery(USER_ID, TODAY.minusDays(501), TODAY, OccurrenceStatus.PENDING, new PageQuery(1, 50)));
        assertThat(oneMore.overdueTruncated()).as("501 vencidas na janela").isTrue();
        assertThat(oneMore.items().get(0).date()).isEqualTo(TODAY.minusDays(500));
        assertThat(daily.recurrence().id()).isNotNull();
    }

    @Test
    @DisplayName("S-244: Idempotency-Key opcional na criação: a mesma chave e o mesmo comando devolvem a mesma recorrência (nada novo); outro comando é IDEMPOTENCY_KEY_REUSED; sem chave não toca a idempotência")
    void deveCriarComIdempotencia() {
        UUID key = UUID.fromString("00000000-0000-0000-0000-0000000a0003");
        when(idempotency.claim(eq(USER_ID), eq("POST /v1/recurrences"), eq(key), any(), any(), any(), any())).thenReturn(true);
        var command = new CreateRecurrenceCommand(USER_ID, TransactionType.EXPENSE, Money.of("119.00"), "Internet", bills.id(), nubank.id(),
                PaymentMethod.PIX, null, Frequency.MONTHLY, null, null, LocalDate.of(2026, 10, 10), null, null, key);
        RecurrenceView first = create.handle(command);
        var hash = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(idempotency).claim(eq(USER_ID), eq("POST /v1/recurrences"), eq(key), hash.capture(), eq(first.recurrence().id()), any(), any());

        when(idempotency.claim(eq(USER_ID), any(), eq(key), any(), any(), any(), any())).thenReturn(false);
        when(idempotency.find(USER_ID, "POST /v1/recurrences", key))
                .thenReturn(java.util.Optional.of(new com.moneylog.core.transaction.app.gateways.IdempotencyGateway.Claim(hash.getValue(), first.recurrence().id(), NOW)));
        assertThat(create.handle(command).recurrence().id()).isEqualTo(first.recurrence().id());
        assertThat(list.handle(new ListRecurrencesQuery(USER_ID, null, PageQuery.first())).items()).hasSize(1);
        var other = new CreateRecurrenceCommand(USER_ID, TransactionType.EXPENSE, Money.of("120.00"), "Internet", bills.id(), nubank.id(),
                PaymentMethod.PIX, null, Frequency.MONTHLY, null, null, LocalDate.of(2026, 10, 10), null, null, key);
        assertThatThrownBy(() -> create.handle(other)).isInstanceOf(com.moneylog.core.transaction.exception.TransactionExceptions.IdempotencyKeyReusedException.class);
        assertThat(list.handle(new ListRecurrencesQuery(USER_ID, null, PageQuery.first())).items()).hasSize(1);
        create.handle(internet(LocalDate.of(2026, 10, 10)));
        verify(idempotency, org.mockito.Mockito.times(3)).claim(any(), any(), any(), any(), any(), any(), any());
    }
}
