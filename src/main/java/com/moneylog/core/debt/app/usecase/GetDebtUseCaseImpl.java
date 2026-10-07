package com.moneylog.core.debt.app.usecase;

import com.moneylog.core.category.app.gateways.CategoryGateway;
import com.moneylog.core.debt.app.DebtView;
import com.moneylog.core.debt.app.DebtViews;
import com.moneylog.core.debt.app.gateways.DebtGateway;
import com.moneylog.core.debt.app.gateways.PersonGateway;
import com.moneylog.core.debt.exception.DebtExceptions.DebtNotFoundException;
import com.moneylog.core.sk.time.TimeProvider;

public class GetDebtUseCaseImpl implements GetDebtUseCase {

    private final DebtGateway debts;
    private final PersonGateway people;
    private final CategoryGateway categories;
    private final TimeProvider time;

    public GetDebtUseCaseImpl(DebtGateway debts, PersonGateway people, CategoryGateway categories, TimeProvider time) {
        this.debts = debts;
        this.people = people;
        this.categories = categories;
        this.time = time;
    }

    @Override
    public DebtView handle(GetDebtQuery query) {
        return DebtViews.one(people, debts, categories, query.userId(), debts.findById(query.userId(), query.debtId())
                .orElseThrow(() -> new DebtNotFoundException(query.debtId())), time.today());
    }
}
