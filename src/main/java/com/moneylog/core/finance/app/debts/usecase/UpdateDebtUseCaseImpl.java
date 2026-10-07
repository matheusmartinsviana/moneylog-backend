package com.moneylog.core.finance.app.debts.usecase;

import com.moneylog.core.category.app.gateways.CategoryGateway;
import com.moneylog.core.debt.app.DebtView;
import com.moneylog.core.debt.app.DebtViews;
import com.moneylog.core.debt.app.gateways.DebtGateway;
import com.moneylog.core.debt.app.gateways.PersonGateway;
import com.moneylog.core.debt.domain.Debt;
import com.moneylog.core.debt.exception.DebtExceptions.DebtNotFoundException;
import com.moneylog.core.sk.exception.VersionConflictException;
import com.moneylog.core.sk.persistence.Versioned;
import com.moneylog.core.sk.time.TimeProvider;

public class UpdateDebtUseCaseImpl implements UpdateDebtUseCase {

    private final PersonGateway people;
    private final DebtGateway debts;
    private final CategoryGateway categories;
    private final TimeProvider time;

    public UpdateDebtUseCaseImpl(PersonGateway people, DebtGateway debts, CategoryGateway categories, TimeProvider time) {
        this.people = people;
        this.debts = debts;
        this.categories = categories;
        this.time = time;
    }

    @Override
    public DebtView handle(UpdateDebtCommand command) {
        debts.lockForUpdate(command.userId(), command.debtId());
        Versioned<Debt> current = debts.findById(command.userId(), command.debtId())
                .orElseThrow(() -> new DebtNotFoundException(command.debtId()));
        if (current.version() != command.version()) {
            throw new VersionConflictException(command.version(), current.version());
        }
        Debt debt = current.value();
        debt.updateDetails(command.description(), command.expectedDate());
        Versioned<Debt> saved = debts.update(debt, command.version());
        return DebtViews.one(people, debts, categories, command.userId(), saved, time.today());
    }
}
