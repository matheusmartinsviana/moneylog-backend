package com.moneylog.core.category.util.factory;

import static com.moneylog.core.util.Ids.USER_ID;

import java.util.UUID;

import com.moneylog.core.category.domain.Category;
import com.moneylog.core.category.domain.CategoryKind;

public final class CategoryTestFactory {

    public static final UUID ALIMENTACAO_ID = UUID.fromString("00000000-0000-0000-0000-00000000c001");
    public static final UUID SALARIO_ID = UUID.fromString("00000000-0000-0000-0000-00000000c002");

    private CategoryTestFactory() {
    }

    /** "Alimentação", despesa, de sistema. */
    public static Category umaCategoriaDeDespesa() {
        return Category.createSystem(ALIMENTACAO_ID, USER_ID, "Alimentação", CategoryKind.EXPENSE);
    }

    /** "Salário", receita, de sistema. */
    public static Category umaCategoriaDeReceita() {
        return Category.createSystem(SALARIO_ID, USER_ID, "Salário", CategoryKind.INCOME);
    }

    public static Category umaCategoriaArquivada() {
        return Category.restore(ALIMENTACAO_ID, USER_ID, "Alimentação", CategoryKind.EXPENSE, true, true, 2L);
    }
}
