package com.moneylog.core.finance;

import static com.moneylog.core.finance.util.FinanceScenarioFactory.BASE_DATE;
import static com.moneylog.core.finance.util.FinanceScenarioFactory.umCenarioBase;
import static com.moneylog.core.util.Ids.USER_ID;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.moneylog.core.category.domain.Category;
import com.moneylog.core.category.domain.CategoryKind;
import com.moneylog.core.debt.domain.Debt;
import com.moneylog.core.debt.domain.DebtDirection;
import com.moneylog.core.debt.util.factory.PersonTestFactory;
import com.moneylog.core.finance.domain.EntryLens;
import com.moneylog.core.finance.domain.EntryReport;
import com.moneylog.core.finance.domain.FinancialRecords;
import com.moneylog.core.finance.domain.SpendingByCategory;
import com.moneylog.core.finance.util.FinanceScenarioFactory;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.time.Competence;
import com.moneylog.core.transaction.domain.AccountTransactions;
import com.moneylog.core.transaction.domain.PaymentMethod;
import com.moneylog.core.transaction.domain.RefundOrigin;
import com.moneylog.core.transaction.domain.Transaction;

@DisplayName("Consumo por categoria (T185, S-286): base = Σ categorias positivas, share arredondado no backend")
class ConsumoPorCategoriaTest {

    private static final Competence OCTOBER = Competence.parse("2026-10");

    private final Map<UUID, String> names = new HashMap<>();
    private final FinanceScenarioFactory s = umCenarioBase();

    private Category category(String name) {
        Category c = Category.create(UUID.randomUUID(), USER_ID, name, CategoryKind.EXPENSE);
        names.put(c.id(), name);
        return c;
    }

    private Transaction spend(Category category, String amount, LocalDate date) {
        Transaction tx = AccountTransactions.expense(s.header(date, "Gasto " + category.name()), Money.of(amount), s.nubank, category,
                PaymentMethod.PIX);
        s.transactions.add(tx);
        return tx;
    }

    private SpendingByCategory spending(int limit, Competence month) {
        FinancialRecords records = s.records();
        EntryReport report = EntryReport.of(records, records.ledger(), EntryLens.CONSUMPTION, month);
        return SpendingByCategory.of(report, limit, id -> names.getOrDefault(id, ""));
    }

    @Test
    @DisplayName("CA: 8 categorias com limit 5 → as 5 maiores em ordem decrescente e other soma as 3 restantes; total = entries.total")
    void deveSepararAsMaioresEAgruparORestante() {
        List<Category> categories = new ArrayList<>();
        String[] amounts = {"10.00", "80.00", "30.00", "70.00", "20.00", "60.00", "40.00", "50.00"};
        for (int i = 0; i < amounts.length; i++) {
            Category c = category("Cat " + i);
            categories.add(c);
            spend(c, amounts[i], BASE_DATE);
        }

        SpendingByCategory result = spending(5, OCTOBER);

        assertThat(result.items()).extracting(i -> i.amount().toPlainString()).containsExactly("80.00", "70.00", "60.00", "50.00", "40.00");
        assertThat(result.items().get(0).categoryId()).isEqualTo(categories.get(1).id());
        assertThat(result.other().amount()).isEqualTo(Money.of("60.00"));
        assertThat(result.other().categories()).isEqualTo(3);
        assertThat(result.total()).isEqualTo(Money.of("360.00"));
        assertThat(result.base()).isEqualTo(Money.of("360.00"));
        assertThat(result.items().get(0).share()).isEqualByComparingTo(new BigDecimal("0.2222"));
        assertThat(result.items().get(0).share().scale()).isEqualTo(4);
        assertThat(result.other().share()).isEqualByComparingTo(new BigDecimal("0.1667"));
        FinancialRecords records = s.records();
        assertThat(result.total()).isEqualTo(EntryReport.of(records, records.ledger(), EntryLens.CONSUMPTION, OCTOBER).total());
    }

    @Test
    @DisplayName("CA: reembolso de mês seguinte que deixa a categoria negativa → ela sai dos itens e de other, base = Σ positivas e Σ share nunca passa de 1")
    void deveTirarACategoriaNegativaDaBase() {
        Category a = category("A");
        Category b = category("B");
        Category c = category("C");
        spend(a, "100.00", BASE_DATE);
        spend(b, "50.00", BASE_DATE);
        Transaction original = spend(c, "20.00", LocalDate.of(2026, 9, 20));
        s.transactions.add(AccountTransactions.refundToAccount(s.header(LocalDate.of(2026, 10, 12), "Reembolso grande"), Money.of("20.00"), s.nubank,
                c, RefundOrigin.firstRefundOf(original)));
        spend(c, "0.01", BASE_DATE);
        s.transactions.add(AccountTransactions.refundToAccount(s.header(LocalDate.of(2026, 10, 13), "Reembolso 2"), Money.of("30.00"), s.nubank, c,
                RefundOrigin.firstRefundOf(spend(c, "30.00", LocalDate.of(2026, 9, 21)))));

        SpendingByCategory result = spending(5, OCTOBER);

        // C em outubro: +0,01 − 20,00 − 30,00 = −49,99 (reembolsos de compras de setembro)
        assertThat(result.items()).extracting(SpendingByCategory.Item::categoryId).containsExactly(a.id(), b.id());
        assertThat(result.other()).isNull();
        assertThat(result.total()).as("o total líquido já considera a categoria negativa").isEqualTo(Money.of("100.01"));
        assertThat(result.base()).isEqualTo(Money.of("150.00"));
        assertThat(result.items().get(0).share()).isEqualByComparingTo("0.6667");
        assertThat(result.items().get(1).share()).isEqualByComparingTo("0.3333");
        BigDecimal sum = result.items().stream().map(SpendingByCategory.Item::share).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sum).isLessThanOrEqualTo(new BigDecimal("1.0000"));
        assertThat(result.items().get(0).amount().add(result.items().get(1).amount())).isEqualTo(result.base());
    }

    @Test
    @DisplayName("CA: três categorias iguais → 0.3333 cada e a soma 0.9999 não é 'corrigida'")
    void deveManterOArredondamentoDoBackend() {
        spend(category("A"), "10.00", BASE_DATE);
        spend(category("B"), "10.00", BASE_DATE);
        spend(category("C"), "10.00", BASE_DATE);

        SpendingByCategory result = spending(5, OCTOBER);

        assertThat(result.items()).extracting(i -> i.share().toPlainString()).containsExactly("0.3333", "0.3333", "0.3333");
        BigDecimal sum = result.items().stream().map(SpendingByCategory.Item::share).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sum).isEqualByComparingTo("0.9999");
    }

    @Test
    @DisplayName("empate de valor: por nome sem diferenciar caixa e depois id; HALF_UP em 0,00005")
    void deveDesempatarPorNome() {
        Category zeta = category("Zeta");
        Category alfa = category("alfa");
        Category beta = category("Beta");
        spend(zeta, "10.00", BASE_DATE);
        spend(alfa, "10.00", BASE_DATE);
        spend(beta, "10.00", BASE_DATE);

        SpendingByCategory result = spending(2, OCTOBER);

        assertThat(result.items()).extracting(SpendingByCategory.Item::categoryId).containsExactly(alfa.id(), beta.id());
        assertThat(result.other().amount()).isEqualTo(Money.of("10.00"));
        assertThat(result.other().categories()).isEqualTo(1);

        // 1 de 20.000 centavos = 0,00005 → HALF_UP → 0.0001
        FinanceScenarioFactory big = umCenarioBase();
        Category x = category("X");
        Category y = category("Y");
        big.transactions.add(AccountTransactions.expense(big.header(BASE_DATE, "x"), Money.of("0.01"), big.nubank, x, PaymentMethod.PIX));
        big.transactions.add(AccountTransactions.expense(big.header(BASE_DATE, "y"), Money.of("199.99"), big.nubank, y, PaymentMethod.PIX));
        FinancialRecords records = big.records();
        SpendingByCategory half = SpendingByCategory.of(EntryReport.of(records, records.ledger(), EntryLens.CONSUMPTION, OCTOBER), 5,
                id -> names.getOrDefault(id, ""));
        assertThat(half.items().get(1).share().toPlainString()).isEqualTo("0.0001");
    }

    @Test
    @DisplayName("consumo de dívida (S-137) entra na categoria dele, como em /v1/entries; sem consumo → items [] e other null")
    void deveContarADividaEFicarVazio() {
        Category food = s.alimentacao;
        names.put(food.id(), food.name());
        s.debts.add(Debt.withoutMovement(UUID.fromString("00000000-0000-0000-0000-0000000d0e01"), USER_ID, PersonTestFactory.umPedro(),
                DebtDirection.PAYABLE, Money.of("80.00"), BASE_DATE, null, "jantar", food));

        SpendingByCategory withDebt = spending(5, OCTOBER);
        assertThat(withDebt.items()).hasSize(1);
        assertThat(withDebt.items().get(0).amount()).isEqualTo(Money.of("80.00"));

        SpendingByCategory empty = spending(5, Competence.parse("2026-01"));
        assertThat(empty.items()).isEmpty();
        assertThat(empty.other()).isNull();
        assertThat(empty.total().isZero()).isTrue();
        assertThat(empty.base().isZero()).isTrue();
    }

    @Test
    @DisplayName("limit menor que 1 é recusado")
    void deveRecusarLimitInvalido() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> spending(0, OCTOBER));
    }
}
