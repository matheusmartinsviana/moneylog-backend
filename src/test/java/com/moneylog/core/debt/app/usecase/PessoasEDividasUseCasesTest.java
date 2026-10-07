package com.moneylog.core.debt.app.usecase;

import static com.moneylog.core.util.Ids.OTHER_USER_ID;
import static com.moneylog.core.util.Ids.USER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.moneylog.core.category.app.gateways.CategoryGateway;
import com.moneylog.core.category.domain.Category;
import com.moneylog.core.category.util.factory.CategoryTestFactory;
import com.moneylog.core.debt.app.DebtView;
import com.moneylog.core.debt.app.PersonView;
import com.moneylog.core.debt.app.gateways.DebtGateway.DebtFilter;
import com.moneylog.core.debt.app.usecase.ArchivePersonUseCase.ArchivePersonCommand;
import com.moneylog.core.debt.app.usecase.CreatePersonUseCase.CreatePersonCommand;
import com.moneylog.core.debt.app.usecase.GetDebtUseCase.GetDebtQuery;
import com.moneylog.core.debt.app.usecase.GetPersonUseCase.GetPersonQuery;
import com.moneylog.core.debt.app.usecase.ListDebtsUseCase.ListDebtsQuery;
import com.moneylog.core.debt.app.usecase.ListPeopleUseCase.ListPeopleQuery;
import com.moneylog.core.debt.app.usecase.UnarchivePersonUseCase.UnarchivePersonCommand;
import com.moneylog.core.debt.app.usecase.UpdatePersonUseCase.UpdatePersonCommand;
import com.moneylog.core.debt.domain.Debt;
import com.moneylog.core.debt.domain.DebtDirection;
import com.moneylog.core.debt.domain.DebtStatus;
import com.moneylog.core.debt.domain.Person;
import com.moneylog.core.debt.domain.PersonBalance;
import com.moneylog.core.debt.exception.DebtExceptions.DebtNotFoundException;
import com.moneylog.core.debt.exception.DebtExceptions.PersonArchiveStateException;
import com.moneylog.core.debt.exception.DebtExceptions.PersonNameRequiredException;
import com.moneylog.core.debt.exception.DebtExceptions.PersonNotFoundException;
import com.moneylog.core.debt.util.InMemoryDebtGateway;
import com.moneylog.core.debt.util.InMemoryPersonGateway;
import com.moneylog.core.sk.exception.VersionConflictException;
import com.moneylog.core.sk.id.IdGenerator;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.page.PageQuery;
import com.moneylog.core.sk.time.TimeProvider;

/** Casos de uso de pessoas (T74) e de leitura de dívidas (T75) com adaptadores em memória das portas. */
@DisplayName("Pessoas e leitura de dívidas: casos de uso (T74, T75)")
class PessoasEDividasUseCasesTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 10);

    private final InMemoryPersonGateway people = new InMemoryPersonGateway();
    private final InMemoryDebtGateway debts = new InMemoryDebtGateway();
    private final CategoryGateway categories = mock(CategoryGateway.class);
    private final TimeProvider time = mock(TimeProvider.class);
    private final Category food = CategoryTestFactory.umaCategoriaDeDespesa();
    private int sequence = 1;

    private final IdGenerator ids = () -> UUID.fromString(String.format("00000000-0000-0000-0000-0000000a%04d", sequence++));
    private CreatePersonUseCaseImpl create;
    private UpdatePersonUseCaseImpl update;
    private ArchivePersonUseCaseImpl archive;
    private UnarchivePersonUseCaseImpl unarchive;
    private GetPersonUseCaseImpl getPerson;
    private ListPeopleUseCaseImpl listPeople;
    private GetDebtUseCaseImpl getDebt;
    private ListDebtsUseCaseImpl listDebts;

    @BeforeEach
    void setUp() {
        when(time.today()).thenReturn(TODAY);
        when(categories.findById(USER_ID, food.id())).thenReturn(Optional.of(food));
        create = new CreatePersonUseCaseImpl(people, debts, ids, time);
        update = new UpdatePersonUseCaseImpl(people, debts, time);
        archive = new ArchivePersonUseCaseImpl(people, debts, time);
        unarchive = new UnarchivePersonUseCaseImpl(people, debts, time);
        getPerson = new GetPersonUseCaseImpl(people, debts, time);
        listPeople = new ListPeopleUseCaseImpl(people, debts, time);
        getDebt = new GetDebtUseCaseImpl(debts, people, categories, time);
        listDebts = new ListDebtsUseCaseImpl(debts, people, categories, time);
    }

    private PersonView person(String name) {
        return create.handle(new CreatePersonCommand(USER_ID, name, null));
    }

    private Debt debtOf(Person person, DebtDirection direction, String amount, LocalDate date, LocalDate expected, Category category) {
        Debt debt = Debt.withoutMovement(ids.next(), USER_ID, person, direction, Money.of(amount), date, expected, "d" + sequence, category);
        debts.insert(debt);
        return debt;
    }

    @Test
    @DisplayName("criar: versão 0, ativa, sem saldo; homônimos são aceitos; nome vazio é recusado")
    void deveCriarPessoas() {
        PersonView joao = person("João");
        assertThat(joao.stored().version()).isZero();
        assertThat(joao.person().isArchived()).isFalse();
        assertThat(joao.balance().net()).isEqualTo(Money.of("0.00"));
        assertThat(joao.balance().direction()).isEqualTo(PersonBalance.Direction.NONE);
        assertThat(joao.debts()).isEmpty();
        assertThat(person("João").person().id()).isNotEqualTo(joao.person().id());
        assertThatThrownBy(() -> person("  ")).isInstanceOf(PersonNameRequiredException.class);
    }

    @Test
    @DisplayName("editar: versão atual muda nome e observação e sobe a versão; versão velha e pessoa de outro usuário são recusadas")
    void deveEditar() {
        PersonView joao = person("João");
        PersonView edited = update.handle(new UpdatePersonCommand(USER_ID, joao.person().id(), "João Pedro", "amigo", 0));
        assertThat(edited.person().name()).isEqualTo("João Pedro");
        assertThat(edited.person().notes()).isEqualTo("amigo");
        assertThat(edited.stored().version()).isEqualTo(1);
        assertThatThrownBy(() -> update.handle(new UpdatePersonCommand(USER_ID, joao.person().id(), "x", null, 0)))
                .isInstanceOf(VersionConflictException.class);
        assertThatThrownBy(() -> update.handle(new UpdatePersonCommand(OTHER_USER_ID, joao.person().id(), "x", null, 1)))
                .isInstanceOf(PersonNotFoundException.class);
        assertThatThrownBy(() -> update.handle(new UpdatePersonCommand(USER_ID, joao.person().id(), "", null, 1)))
                .isInstanceOf(PersonNameRequiredException.class);
        assertThat(people.locks()).isGreaterThanOrEqualTo(3);
    }

    @Test
    @DisplayName("arquivar e desarquivar: o estado errado é recusado e a versão sobe a cada gravação")
    void deveArquivarEDesarquivar() {
        PersonView joao = person("João");
        UUID id = joao.person().id();
        assertThat(archive.handle(new ArchivePersonCommand(USER_ID, id)).person().isArchived()).isTrue();
        assertThatThrownBy(() -> archive.handle(new ArchivePersonCommand(USER_ID, id))).isInstanceOf(PersonArchiveStateException.class)
                .extracting("code").isEqualTo("PERSON_ALREADY_ARCHIVED");
        PersonView back = unarchive.handle(new UnarchivePersonCommand(USER_ID, id));
        assertThat(back.person().isArchived()).isFalse();
        assertThat(back.stored().version()).isEqualTo(2);
        assertThatThrownBy(() -> unarchive.handle(new UnarchivePersonCommand(USER_ID, id))).isInstanceOf(PersonArchiveStateException.class)
                .extracting("code").isEqualTo("PERSON_NOT_ARCHIVED");
        assertThatThrownBy(() -> archive.handle(new ArchivePersonCommand(USER_ID, UUID.randomUUID()))).isInstanceOf(PersonNotFoundException.class);
        assertThatThrownBy(() -> unarchive.handle(new UnarchivePersonCommand(USER_ID, UUID.randomUUID()))).isInstanceOf(PersonNotFoundException.class);
    }

    @Test
    @DisplayName("detalhe: saldo (net), abertas, próxima previsão e o histórico da mais recente para a mais antiga")
    void deveDetalharAPessoa() {
        PersonView joao = person("João");
        Person domain = joao.person();
        debtOf(domain, DebtDirection.RECEIVABLE, "150.00", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 5), null);
        debtOf(domain, DebtDirection.PAYABLE, "40.00", LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 25), null);
        Debt forgiven = debtOf(domain, DebtDirection.RECEIVABLE, "999.00", LocalDate.of(2026, 10, 9), null, null);
        forgiven.cancel("perdão", TODAY);

        PersonView view = getPerson.handle(new GetPersonQuery(USER_ID, domain.id()));
        assertThat(view.balance().net()).isEqualTo(Money.of("110.00"));
        assertThat(view.balance().openDebts()).isEqualTo(2);
        assertThat(view.balance().nextDueDate()).isEqualTo(LocalDate.of(2026, 10, 25));
        assertThat(view.debts()).hasSize(3);
        assertThat(view.debts().get(0).date()).isEqualTo(LocalDate.of(2026, 10, 9));
        assertThat(view.debts().get(0).status()).isEqualTo(DebtStatus.CANCELED);
        assertThatThrownBy(() -> getPerson.handle(new GetPersonQuery(OTHER_USER_ID, domain.id()))).isInstanceOf(PersonNotFoundException.class);
    }

    @Test
    @DisplayName("lista: filtra por arquivadas e por nome, pagina sem COUNT e calcula o saldo de cada pessoa de uma vez")
    void deveListarPessoas() {
        PersonView joao = person("João");
        PersonView maria = person("Maria");
        person("Mariana");
        debtOf(maria.person(), DebtDirection.PAYABLE, "80.00", TODAY, null, null);
        archive.handle(new ArchivePersonCommand(USER_ID, joao.person().id()));

        assertThat(listPeople.handle(new ListPeopleQuery(USER_ID, false, null, PageQuery.first())).items()).extracting(v -> v.person().name())
                .containsExactly("Maria", "Mariana");
        assertThat(listPeople.handle(new ListPeopleQuery(USER_ID, true, null, PageQuery.first())).items()).hasSize(1);
        assertThat(listPeople.handle(new ListPeopleQuery(USER_ID, null, "mari", PageQuery.first())).items()).hasSize(2);
        var page = listPeople.handle(new ListPeopleQuery(USER_ID, null, null, new PageQuery(1, 2)));
        assertThat(page.items()).hasSize(2);
        assertThat(page.hasNext()).isTrue();
        PersonView listedMaria = listPeople.handle(new ListPeopleQuery(USER_ID, false, "maria", PageQuery.first())).items().get(0);
        assertThat(listedMaria.balance().net()).isEqualTo(Money.of("-80.00"));
        assertThat(listedMaria.debts()).isEmpty();
        assertThat(listPeople.handle(new ListPeopleQuery(OTHER_USER_ID, null, null, PageQuery.first())).items()).isEmpty();
    }

    @Test
    @DisplayName("dívida: status derivado, atraso pelo relógio, ações permitidas, categoria e data das quitações")
    void deveLerADivida() {
        Person person = person("João").person();
        Debt late = debtOf(person, DebtDirection.RECEIVABLE, "300.00", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 1), null);
        Debt consumed = debtOf(person, DebtDirection.PAYABLE, "80.00", LocalDate.of(2026, 10, 2), null, food);
        UUID settlementTx = UUID.fromString("00000000-0000-0000-0000-0000000e9001");
        late.registerSettlement(settlementTx, Money.of("100.00"));
        debts.settlementDate(settlementTx, LocalDate.of(2026, 10, 5));

        DebtView view = getDebt.handle(new GetDebtQuery(USER_ID, late.id()));
        assertThat(view.debt().status()).isEqualTo(DebtStatus.PARTIALLY_SETTLED);
        assertThat(view.overdue()).isTrue();
        assertThat(view.cancelable()).isTrue();
        assertThat(view.voidable()).isFalse();
        assertThat(view.voidBlockedBy()).isEqualTo(DebtView.VoidBlockedBy.DEBT_HAS_SETTLEMENTS);
        assertThat(view.settlements()).hasSize(1);
        assertThat(view.settlements().get(0).date()).isEqualTo(LocalDate.of(2026, 10, 5));
        assertThat(view.person().name()).isEqualTo("João");
        assertThat(view.categoryName()).isNull();

        DebtView withCategory = getDebt.handle(new GetDebtQuery(USER_ID, consumed.id()));
        assertThat(withCategory.categoryName()).isEqualTo(food.name());
        assertThat(withCategory.voidable()).isTrue();
        assertThat(withCategory.voidBlockedBy()).isNull();
        assertThat(withCategory.overdue()).isFalse();
        assertThatThrownBy(() -> getDebt.handle(new GetDebtQuery(OTHER_USER_ID, late.id()))).isInstanceOf(DebtNotFoundException.class);
        assertThatThrownBy(() -> getDebt.handle(new GetDebtQuery(USER_ID, UUID.randomUUID()))).isInstanceOf(DebtNotFoundException.class);

        var overdueOnly = listDebts.handle(new ListDebtsQuery(USER_ID, new DebtFilter(null, null, null, true), PageQuery.first()));
        assertThat(overdueOnly.items()).hasSize(1);
        assertThat(overdueOnly.items().get(0).debt().id()).isEqualTo(late.id());
        var payables = listDebts.handle(new ListDebtsQuery(USER_ID, new DebtFilter(DebtDirection.PAYABLE, DebtStatus.OPEN, person.id(), null),
                PageQuery.first()));
        assertThat(payables.items()).hasSize(1);
        assertThat(listDebts.handle(new ListDebtsQuery(USER_ID, DebtFilter.all(), new PageQuery(1, 1))).hasNext()).isTrue();

        // cancelada, a dívida não tem mais o que cancelar e o estorno do registro também é recusado (DEBT_CLOSED)
        late.cancel("perdão", TODAY);
        DebtView closed = getDebt.handle(new GetDebtQuery(USER_ID, late.id()));
        assertThat(closed.cancelable()).isFalse();
        assertThat(closed.voidBlockedBy()).isEqualTo(DebtView.VoidBlockedBy.DEBT_CLOSED);
    }
}
