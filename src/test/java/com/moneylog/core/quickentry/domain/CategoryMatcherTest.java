package com.moneylog.core.quickentry.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.moneylog.core.category.domain.Category;
import com.moneylog.core.category.domain.CategoryKind;

@DisplayName("Resolução da categoria por chave de sistema ou nome (ADR-034, seção 8)")
class CategoryMatcherTest {

    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private final Category food = Category.createSystem(UUID.randomUUID(), USER, "Alimentação", CategoryKind.EXPENSE);
    private final Category other = Category.createSystem(UUID.randomUUID(), USER, "Outros", CategoryKind.EXPENSE);
    private final Category own = Category.create(UUID.randomUUID(), USER, "Pet Shop", CategoryKind.EXPENSE);
    private final List<Category> active = List.of(food, other, own);
    private final Map<UUID, String> keys = Map.of(food.id(), "FOOD", other.id(), "OTHER_EXPENSE");

    @Test
    @DisplayName("chave de sistema, sem diferenciar caixa")
    void devePorChaveDeSistema() {
        assertThat(CategoryMatcher.find("FOOD", active, keys)).contains(food);
        assertThat(CategoryMatcher.find("food", active, keys)).contains(food);
        assertThat(CategoryMatcher.find(" other_expense ", active, keys)).contains(other);
    }

    @Test
    @DisplayName("nome sem acento e sem caixa")
    void devePorNome() {
        assertThat(CategoryMatcher.find("alimentacao", active, keys)).contains(food);
        assertThat(CategoryMatcher.find("ALIMENTAÇÃO", active, keys)).contains(food);
        assertThat(CategoryMatcher.find("pet shop", active, keys)).contains(own);
    }

    @Test
    @DisplayName("inexistente, vazia ou nula não resolve; chave de categoria própria não vale como chave de sistema")
    void deveNaoResolver() {
        assertThat(CategoryMatcher.find("Viagem", active, keys)).isEmpty();
        assertThat(CategoryMatcher.find("", active, keys)).isEmpty();
        assertThat(CategoryMatcher.find("  ", active, keys)).isEmpty();
        assertThat(CategoryMatcher.find(null, active, keys)).isEmpty();
        assertThat(CategoryMatcher.find("SALARY", active, keys)).isEmpty();
        assertThat(CategoryMatcher.find("FOOD", List.of(own), keys)).isEmpty();
    }
}
