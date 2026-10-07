package com.moneylog.core.finance;

import static com.moneylog.core.util.Ids.USER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.moneylog.core.category.app.gateways.CategoryGateway;
import com.moneylog.core.category.domain.Category;
import com.moneylog.core.category.domain.CategoryKind;
import com.moneylog.core.debt.domain.Debt;
import com.moneylog.core.debt.domain.DebtDirection;
import com.moneylog.core.debt.util.factory.PersonTestFactory;
import com.moneylog.core.finance.app.gateways.FinancialRecordsGateway;
import com.moneylog.core.finance.app.transactions.EntryListing;
import com.moneylog.core.finance.app.transactions.usecase.ListEntriesUseCase.ListEntriesQuery;
import com.moneylog.core.finance.app.transactions.usecase.ListEntriesUseCaseImpl;
import com.moneylog.core.finance.domain.ConsumptionReport;
import com.moneylog.core.finance.domain.EntryLens;
import com.moneylog.core.finance.domain.EntryReport;
import com.moneylog.core.finance.domain.FinancialRecords;
import com.moneylog.core.finance.util.FinanceScenarioFactory;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.page.PageQuery;
import com.moneylog.core.sk.time.Competence;
import com.moneylog.core.transaction.app.query.EntryFilter;

/**
 * S-137 e S-79: o consumo de uma dívida {@code PAYABLE} com categoria (um amigo pagou meu jantar de 80,00) aparece no dashboard
 * ({@code debtConsumption}, dentro de {@code consumption}) e na visão por competência (linha sem movimentação), então a lista e o
 * dashboard continuam fechando.
 */
@DisplayName("Consumo de dívida nas visões por competência e no dashboard (S-137, S-79)")
class ConsumoDeDividaNasVisoesTest {

    private static final LocalDate DATE = LocalDate.of(2026, 10, 10);
    private static final Competence OCTOBER = Competence.from(DATE);

    private final FinanceScenarioFactory scenario = FinanceScenarioFactory.umCenarioBase();
    private final Category food = scenario.alimentacao;

    private FinancialRecords recordsWithDinner() {
        scenario.caso1DespesaPix();
        scenario.debts.add(Debt.withoutMovement(UUID.fromString("00000000-0000-0000-0000-0000000d0e01"), USER_ID, PersonTestFactory.umPedro(),
                DebtDirection.PAYABLE, Money.of("80.00"), DATE, null, "jantar pago pelo amigo", food));
        return scenario.records();
    }

    @Test
    @DisplayName("ConsumptionReport separa o consumo da dívida e mantém a identidade: consumption = pago + cartão − reembolso + dívida")
    void deveSepararOConsumoDaDivida() {
        FinancialRecords records = recordsWithDinner();
        ConsumptionReport month = ConsumptionReport.of(records, records.ledger(), OCTOBER);
        assertThat(month.debtConsumption()).isEqualTo(Money.of("80.00"));
        assertThat(month.expensesPaid()).isEqualTo(Money.of("100.00"));
        assertThat(month.consumption()).isEqualTo(Money.of("180.00"));
        assertThat(month.consumption()).isEqualTo(month.expensesPaid().add(month.credit()).subtract(month.refunds()).add(month.debtConsumption()));
    }

    @Test
    @DisplayName("EntryReport traz a linha da dívida (sem movimentação, com debtId e a descrição da dívida); o total é o do dashboard")
    void deveTrazerALinhaDaDivida() {
        FinancialRecords records = recordsWithDinner();
        EntryReport all = EntryReport.of(records, records.ledger(), EntryLens.CONSUMPTION, OCTOBER);
        assertThat(all.total()).isEqualTo(ConsumptionReport.of(records, records.ledger(), OCTOBER).consumption());
        EntryReport.Line debtLine = all.lines().stream().filter(l -> l.debtId() != null).findFirst().orElseThrow();
        assertThat(debtLine.transactionId()).isNull();
        assertThat(debtLine.amount()).isEqualTo(Money.of("80.00"));
        assertThat(debtLine.description()).isEqualTo("jantar pago pelo amigo");
        assertThat(debtLine.categoryId()).isEqualTo(food.id());
        assertThat(all.byCategory().get(food.id())).isEqualTo(Money.of("180.00"));
        // com filtro por movimentação (5 argumentos) a linha de dívida fica de fora; o filtro de categoria da dívida a traz de volta
        EntryReport filtered = EntryReport.of(records, records.ledger(), EntryLens.CONSUMPTION, OCTOBER, t -> true);
        assertThat(filtered.total()).isEqualTo(Money.of("100.00"));
        EntryReport byCategory = EntryReport.of(records, records.ledger(), EntryLens.CONSUMPTION, OCTOBER, t -> true, food.id()::equals);
        assertThat(byCategory.total()).isEqualTo(Money.of("180.00"));
        // a lente de receita não tem linha de dívida
        assertThat(EntryReport.of(records, records.ledger(), EntryLens.INCOME, OCTOBER).lines()).isEmpty();
    }

    @Test
    @DisplayName("ListEntriesUseCase: as linhas de dívida vão em debtItems (completas), entram no total e respeitam os filtros (conta e meio de pagamento as excluem)")
    void deveListarAsLinhasDeDivida() {
        FinancialRecords records = recordsWithDinner();
        FinancialRecordsGateway gateway = mock(FinancialRecordsGateway.class);
        CategoryGateway categories = mock(CategoryGateway.class);
        when(gateway.load(USER_ID)).thenReturn(records);
        Category other = Category.create(UUID.randomUUID(), USER_ID, "Lazer", CategoryKind.EXPENSE);
        when(categories.list(USER_ID, null, true)).thenReturn(List.of(food, other));
        ListEntriesUseCaseImpl useCase = new ListEntriesUseCaseImpl(gateway, categories);

        EntryListing all = useCase.handle(new ListEntriesQuery(USER_ID, OCTOBER, EntryLens.CONSUMPTION, new EntryFilter(null, null, null),
                PageQuery.first()));
        assertThat(all.total()).isEqualTo(Money.of("180.00"));
        assertThat(all.items().items()).hasSize(1);
        assertThat(all.debtItems()).hasSize(1);
        assertThat(all.debtItems().get(0).line().debtId()).isNotNull();
        assertThat(all.debtItems().get(0).category().name()).isEqualTo(food.name());

        EntryListing byCategory = useCase.handle(new ListEntriesQuery(USER_ID, OCTOBER, EntryLens.CONSUMPTION,
                new EntryFilter(food.id(), null, null), PageQuery.first()));
        assertThat(byCategory.total()).isEqualTo(Money.of("180.00"));
        assertThat(byCategory.debtItems()).hasSize(1);
        EntryListing otherCategory = useCase.handle(new ListEntriesQuery(USER_ID, OCTOBER, EntryLens.CONSUMPTION,
                new EntryFilter(other.id(), null, null), PageQuery.first()));
        assertThat(otherCategory.total()).isEqualTo(Money.of("0.00"));
        assertThat(otherCategory.debtItems()).isEmpty();
        EntryListing byAccount = useCase.handle(new ListEntriesQuery(USER_ID, OCTOBER, EntryLens.CONSUMPTION,
                new EntryFilter(null, scenario.nubank.id(), null), PageQuery.first()));
        assertThat(byAccount.total()).isEqualTo(Money.of("100.00"));
        assertThat(byAccount.debtItems()).isEmpty();
        EntryListing byMethod = useCase.handle(new ListEntriesQuery(USER_ID, OCTOBER, EntryLens.CONSUMPTION,
                new EntryFilter(null, null, com.moneylog.core.transaction.domain.PaymentMethod.PIX), PageQuery.first()));
        assertThat(byMethod.debtItems()).isEmpty();
        assertThat(new EntryListing(all.items(), all.total(), all.byCategory()).debtItems()).isEmpty();
    }

    @Test
    @DisplayName("S-238: debtItems só na primeira página; concatenar as páginas não duplica as linhas e a soma bate com o total")
    void deveDevolverDebtItemsSoNaPrimeiraPagina() {
        FinancialRecords records = recordsWithDinner();
        scenario.debts.add(Debt.withoutMovement(UUID.fromString("00000000-0000-0000-0000-0000000d0e02"), USER_ID, PersonTestFactory.umPedro(),
                DebtDirection.PAYABLE, Money.of("10.00"), DATE, null, "outro jantar", food));
        records = scenario.records();
        FinancialRecordsGateway gateway = mock(FinancialRecordsGateway.class);
        CategoryGateway categories = mock(CategoryGateway.class);
        when(gateway.load(USER_ID)).thenReturn(records);
        when(categories.list(USER_ID, null, true)).thenReturn(List.of(food));
        ListEntriesUseCaseImpl useCase = new ListEntriesUseCaseImpl(gateway, categories);

        Money sum = Money.of("0.00");
        int debtLines = 0;
        for (int page = 1; page <= 3; page++) {
            EntryListing listing = useCase.handle(new ListEntriesQuery(USER_ID, OCTOBER, EntryLens.CONSUMPTION, new EntryFilter(null, null, null),
                    new PageQuery(page, 1)));
            assertThat(listing.total()).isEqualTo(Money.of("190.00"));
            for (EntryListing.Row row : listing.items().items()) {
                sum = sum.add(row.line().amount());
            }
            for (EntryListing.DebtRow row : listing.debtItems()) {
                sum = sum.add(row.line().amount());
                debtLines++;
            }
            if (page > 1) {
                assertThat(listing.debtItems()).as("página " + page).isEmpty();
            } else {
                assertThat(listing.debtItems()).hasSize(2);
            }
        }
        assertThat(debtLines).isEqualTo(2);
        assertThat(sum).isEqualTo(Money.of("190.00"));
    }
}
