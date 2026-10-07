package com.moneylog.infra.reports.controller;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.moneylog.core.category.domain.Category;
import com.moneylog.core.finance.app.reports.usecase.GetSpendingByCategoryUseCase.SpendingByCategoryView;
import com.moneylog.core.finance.domain.SpendingByCategory;
import com.moneylog.core.sk.money.Money;

/** {@code SpendingByCategory} do contrato (S-286): {@code share} já arredondado no backend (4 casas); o front não normaliza. */
public record SpendingByCategoryResponse(String basis, LocalDate from, LocalDate to, Money total, Money base, List<Item> items, Other other) {

    public record CategoryRef(UUID id, String name) {
    }

    public record Item(CategoryRef category, Money amount, String share) {
    }

    public record Other(Money amount, String share, int categories) {
    }

    public static SpendingByCategoryResponse of(SpendingByCategoryView view) {
        SpendingByCategory s = view.spending();
        List<Item> items = s.items().stream()
                .map(i -> new Item(ref(i.categoryId(), view), i.amount(), i.share() == null ? null : i.share().toPlainString())).toList();
        Other other = s.other() == null ? null
                : new Other(s.other().amount(), s.other().share() == null ? null : s.other().share().toPlainString(), s.other().categories());
        return new SpendingByCategoryResponse("COMPETENCE", s.competence().firstDay(), s.competence().lastDay(), s.total(), s.base(), items, other);
    }

    private static CategoryRef ref(UUID id, SpendingByCategoryView view) {
        Category category = view.categories().get(id);
        return new CategoryRef(id, category == null ? null : category.name());
    }
}
