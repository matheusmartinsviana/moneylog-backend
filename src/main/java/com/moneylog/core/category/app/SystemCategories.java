package com.moneylog.core.category.app;

import java.util.List;

import com.moneylog.core.category.domain.CategoryKind;

public final class SystemCategories {

    public record Definition(String key, String name, CategoryKind kind) {
    }

    public static final List<Definition> ALL = List.of(
            new Definition("FOOD", "Alimentação", CategoryKind.EXPENSE),
            new Definition("TRANSPORT", "Transporte", CategoryKind.EXPENSE),
            new Definition("HOUSING", "Moradia", CategoryKind.EXPENSE),
            new Definition("HEALTH", "Saúde", CategoryKind.EXPENSE),
            new Definition("EDUCATION", "Educação", CategoryKind.EXPENSE),
            new Definition("LEISURE", "Lazer", CategoryKind.EXPENSE),
            new Definition("SUBSCRIPTIONS", "Assinaturas", CategoryKind.EXPENSE),
            new Definition("TECHNOLOGY", "Tecnologia", CategoryKind.EXPENSE),
            new Definition("SHOPPING", "Compras", CategoryKind.EXPENSE),
            new Definition("TAXES", "Impostos", CategoryKind.EXPENSE),
            new Definition("BILLS", "Contas", CategoryKind.EXPENSE),
            new Definition("FAMILY", "Família", CategoryKind.EXPENSE),
            new Definition("OTHER_EXPENSE", "Outros", CategoryKind.EXPENSE),
            new Definition("SALARY", "Salário", CategoryKind.INCOME),
            new Definition("EXTRA_WORK", "Trabalho extra", CategoryKind.INCOME),
            new Definition("OTHER_INCOME", "Outras receitas", CategoryKind.INCOME));

    public static final String DEFAULT_EXPENSE_KEY = "OTHER_EXPENSE";
    public static final String DEFAULT_INCOME_KEY = "OTHER_INCOME";

    private SystemCategories() {
    }
}
