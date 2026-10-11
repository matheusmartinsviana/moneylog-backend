package com.moneylog.core.debt.domain;

import static com.moneylog.core.util.Ids.USER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.moneylog.core.category.domain.Category;
import com.moneylog.core.category.domain.CategoryKind;
import com.moneylog.core.category.exception.CategoryExceptions.CategoryKindMismatchException;
import com.moneylog.core.category.util.factory.CategoryTestFactory;
import com.moneylog.core.debt.exception.DebtExceptions.DebtCategoryNotAllowedException;
import com.moneylog.core.debt.exception.DebtExceptions.DebtOperationRefusedException;
import com.moneylog.core.debt.exception.DebtExceptions.DebtStateInconsistentException;
import com.moneylog.core.debt.util.factory.PersonTestFactory;
import com.moneylog.core.finance.domain.ConsumptionReport;
import com.moneylog.core.finance.domain.FinancialRecords;
import com.moneylog.core.finance.domain.Ledger;
import com.moneylog.core.finance.domain.NetWorth;
import com.moneylog.core.sk.ledger.BookRef;
import com.moneylog.core.sk.ledger.BookType;
import com.moneylog.core.sk.ledger.LedgerEntry;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.time.Competence;
import com.moneylog.core.sk.time.FinancialDates.DateOutOfRangeException;

@DisplayName("Dívida de consumo (S-137), edição de dados e saldo por pessoa (S-142)")
class DebtConsumoEPessoaTest {

    private static final LocalDate DATE = LocalDate.of(2026, 10, 10);
    private static final UUID DEBT_ID = UUID.fromString("00000000-0000-0000-0000-0000000d0c01");

    private final Category food = CategoryTestFactory.umaCategoriaDeDespesa();

    private Debt dinner(Category category) {
        return Debt.withoutMovement(DEBT_ID, USER_ID, PersonTestFactory.umPedro(), DebtDirection.PAYABLE, Money.of("80.00"), DATE,
                LocalDate.of(2026, 11, 5), "jantar pago pelo amigo", category);
    }

    @Nested
    @DisplayName("PAYABLE sem movimentação com categoria")
    class ComCategoria {

        @Test
        @DisplayName("EXPENSE(cat) +80 / PAYABLE −80: consumo +80 na competência, caixa 0, patrimônio −80")
        void deveLancarConsumo() {
            Debt debt = dinner(food);
            assertThat(debt.categoryId()).isEqualTo(food.id());
            assertThat(debt.entries()).hasSize(2);
            assertThat(debt.entries()).extracting(e -> e.posting().book())
                    .containsExactlyInAnyOrder(BookRef.expense(food.id()), BookRef.payable(DEBT_ID));

            FinancialRecords records = new FinancialRecords(List.of(), List.of(), List.of(), List.of(debt));
            Ledger ledger = records.ledger();
            assertThat(ConsumptionReport.of(records, ledger, Competence.from(DATE)).consumption()).isEqualTo(Money.of("80.00"));
            assertThat(NetWorth.at(ledger, DATE).total()).isEqualTo(Money.of("-80.00"));
            assertThat(ledger.balance(BookType.ACCOUNT, DATE)).isEqualTo(Money.of("0.00"));
            assertThat(ledger.balance(BookType.EQUITY, DATE)).isEqualTo(Money.of("0.00"));
        }

        @Test
        @DisplayName("sem categoria continua o caso 8: consumo 0, contra EQUITY")
        void semCategoriaContinuaOCaso8() {
            Debt debt = dinner(null);
            assertThat(debt.categoryId()).isNull();
            FinancialRecords records = new FinancialRecords(List.of(), List.of(), List.of(), List.of(debt));
            assertThat(ConsumptionReport.of(records, records.ledger(), Competence.from(DATE)).consumption()).isEqualTo(Money.of("0.00"));
            assertThat(debt.entries()).extracting(e -> e.posting().bookType()).contains(BookType.EQUITY);
        }

        @Test
        @DisplayName("dívida RECEIVABLE não tem categoria; categoria de receita ou arquivada ou de outro usuário é recusada")
        void deveRecusarCategoriaInvalida() {
            assertThatThrownBy(() -> Debt.withoutMovement(DEBT_ID, USER_ID, PersonTestFactory.umJoao(), DebtDirection.RECEIVABLE,
                    Money.of("10.00"), DATE, null, "x", food)).isInstanceOf(DebtCategoryNotAllowedException.class)
                    .extracting("code").isEqualTo("DEBT_CATEGORY_NOT_ALLOWED");
            assertThatThrownBy(() -> dinner(CategoryTestFactory.umaCategoriaDeReceita())).isInstanceOf(CategoryKindMismatchException.class);
            Category archived = Category.restore(UUID.randomUUID(), USER_ID, "Antiga", CategoryKind.EXPENSE, false, true, 1L);
            assertThatThrownBy(() -> dinner(archived)).isInstanceOf(com.moneylog.core.category.exception.CategoryExceptions.CategoryArchivedException.class);
            Category stranger = Category.create(UUID.randomUUID(), UUID.fromString("00000000-0000-0000-0000-000000000002"), "Outra",
                    CategoryKind.EXPENSE);
            assertThatThrownBy(() -> dinner(stranger)).isInstanceOf(com.moneylog.core.category.exception.CategoryExceptions.CategoryNotFoundException.class);
        }

        @Test
        @DisplayName("perdão zera o saldo contra EQUITY e mantém o consumo (já aconteceu); o estorno do registro desfaz o consumo")
        void deveTratarPerdaoEEstorno() {
            Debt forgiven = dinner(food);
            forgiven.cancel("o amigo me perdoou", DATE);
            FinancialRecords records = new FinancialRecords(List.of(), List.of(), List.of(), List.of(forgiven));
            assertThat(ConsumptionReport.of(records, records.ledger(), Competence.from(DATE)).consumption()).isEqualTo(Money.of("80.00"));
            assertThat(records.ledger().balance(BookType.PAYABLE, DATE)).isEqualTo(Money.of("0.00"));

            Debt voided = dinner(food);
            voided.voidRecord("lancei errado", DATE);
            FinancialRecords after = new FinancialRecords(List.of(), List.of(), List.of(), List.of(voided));
            assertThat(ConsumptionReport.of(after, after.ledger(), Competence.from(DATE)).consumption()).isEqualTo(Money.of("0.00"));
            assertThat(voided.status()).isEqualTo(DebtStatus.VOIDED);
        }

        @Test
        @DisplayName("reidratar mantém a categoria; estado com categoria numa RECEIVABLE ou sem o lançamento da despesa é inconsistente")
        void deveReidratarEValidar() {
            Debt debt = dinner(food);
            Debt restored = Debt.restore(debt.state(), debt.settlements(), debt.entries());
            assertThat(restored.categoryId()).isEqualTo(food.id());
            assertThat(restored.entries()).isEqualTo(debt.entries());

            DebtState wrongDirection = new DebtState(DEBT_ID, USER_ID, PersonTestFactory.JOAO_ID, DebtDirection.RECEIVABLE, DebtOrigin.WITHOUT_MOVEMENT,
                    Money.of("80.00"), "x", DATE, null, null, null, null, null, food.id());
            assertThatThrownBy(() -> Debt.restore(wrongDirection, List.of(), debt.entries())).isInstanceOf(DebtStateInconsistentException.class);
            List<LedgerEntry> equityEntries = dinner(null).entries();
            assertThatThrownBy(() -> Debt.restore(debt.state(), List.of(), equityEntries)).isInstanceOf(DebtStateInconsistentException.class);
            DebtState old = new DebtState(DEBT_ID, USER_ID, PersonTestFactory.PEDRO_ID, DebtDirection.PAYABLE, DebtOrigin.WITHOUT_MOVEMENT,
                    Money.of("80.00"), "x", DATE, null, null, null, null, null);
            assertThat(old.categoryId()).isNull();
        }
    }

    @Nested
    @DisplayName("updateDetails")
    class Edicao {

        @Test
        @DisplayName("muda descrição e previsão; o principal não muda; encerrada recusa; previsão fora da janela é recusada")
        void deveEditarDescricaoEPrevisao() {
            Debt debt = dinner(null);
            debt.updateDetails("  jantar de sábado  ", LocalDate.of(2026, 12, 1));
            assertThat(debt.description()).isEqualTo("jantar de sábado");
            assertThat(debt.expectedDate()).isEqualTo(LocalDate.of(2026, 12, 1));
            assertThat(debt.principal()).isEqualTo(Money.of("80.00"));
            debt.updateDetails(null, null);
            assertThat(debt.description()).isNull();
            assertThat(debt.expectedDate()).isNull();
            assertThatThrownBy(() -> debt.updateDetails("x", LocalDate.of(1990, 1, 1))).isInstanceOf(DateOutOfRangeException.class);

            debt.cancel("perdoada", DATE);
            assertThatThrownBy(() -> debt.updateDetails("tarde", null)).isInstanceOf(DebtOperationRefusedException.class)
                    .extracting("code").isEqualTo("DEBT_CLOSED");
        }

        @Test
        @DisplayName("data da dívida e previsão têm de estar na janela financeira")
        void deveValidarAJanelaDeDatas() {
            assertThatThrownBy(() -> Debt.withoutMovement(DEBT_ID, USER_ID, PersonTestFactory.umPedro(), DebtDirection.PAYABLE,
                    Money.of("1.00"), LocalDate.of(1990, 1, 1), null, "x")).isInstanceOf(DateOutOfRangeException.class);
            assertThatThrownBy(() -> Debt.withoutMovement(DEBT_ID, USER_ID, PersonTestFactory.umPedro(), DebtDirection.PAYABLE,
                    Money.of("1.00"), DATE, LocalDate.of(2200, 1, 1), "x")).isInstanceOf(DateOutOfRangeException.class);
        }
    }

    @Nested
    @DisplayName("PersonBalance")
    class Saldo {

        private Debt receivable(String id, String amount, LocalDate expected) {
            return Debt.withoutMovement(UUID.fromString("00000000-0000-0000-0000-0000000d0d" + id), USER_ID, PersonTestFactory.umJoao(),
                    DebtDirection.RECEIVABLE, Money.of(amount), DATE, expected, "r" + id);
        }

        private Debt payable(String id, String amount, LocalDate expected) {
            return Debt.withoutMovement(UUID.fromString("00000000-0000-0000-0000-0000000d0d" + id), USER_ID, PersonTestFactory.umJoao(),
                    DebtDirection.PAYABLE, Money.of(amount), DATE, expected, "p" + id);
        }

        @Test
        @DisplayName("João com recebível aberto de 150 e passivo de 40: net +110, 2 abertas, próxima previsão a menor data; CANCELED não entra")
        void deveCalcularONet() {
            Debt big = receivable("01", "150.00", LocalDate.of(2026, 11, 5));
            Debt small = payable("02", "40.00", LocalDate.of(2026, 10, 25));
            Debt forgiven = receivable("03", "999.00", null);
            forgiven.cancel("perdão", DATE);
            PersonBalance balance = PersonBalance.of(List.of(big, small, forgiven));
            assertThat(balance.net()).isEqualTo(Money.of("110.00"));
            assertThat(balance.openDebts()).isEqualTo(2);
            assertThat(balance.nextDueDate()).isEqualTo(LocalDate.of(2026, 10, 25));
            assertThat(balance.direction()).isEqualTo(PersonBalance.Direction.RECEIVABLE);
        }

        @Test
        @DisplayName("net negativo = você deve; zero = quite; dívida quitada ou sem previsão não dá próxima data")
        void deveDarADirecao() {
            Debt owed = payable("04", "80.00", null);
            PersonBalance negative = PersonBalance.of(List.of(owed));
            assertThat(negative.net()).isEqualTo(Money.of("-80.00"));
            assertThat(negative.direction()).isEqualTo(PersonBalance.Direction.PAYABLE);
            assertThat(negative.nextDueDate()).isNull();

            PersonBalance none = PersonBalance.of(List.of());
            assertThat(none.net()).isEqualTo(Money.of("0.00"));
            assertThat(none.direction()).isEqualTo(PersonBalance.Direction.NONE);
            assertThat(none.openDebts()).isZero();
        }
    }

    @Test
    @DisplayName("Person.changeNotes limpa o texto em branco e apara as pontas")
    void deveEditarObservacoes() {
        var person = PersonTestFactory.umJoao();
        person.changeNotes("  amigo da faculdade ");
        assertThat(person.notes()).isEqualTo("amigo da faculdade");
        person.changeNotes("   ");
        assertThat(person.notes()).isNull();
        person.changeNotes(null);
        assertThat(person.notes()).isNull();
    }
}
