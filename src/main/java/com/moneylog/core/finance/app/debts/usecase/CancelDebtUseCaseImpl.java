package com.moneylog.core.finance.app.debts.usecase;

import com.moneylog.core.category.app.gateways.CategoryGateway;
import com.moneylog.core.debt.app.DebtView;
import com.moneylog.core.debt.app.DebtViews;
import com.moneylog.core.debt.app.gateways.DebtGateway;
import com.moneylog.core.debt.app.gateways.PersonGateway;
import com.moneylog.core.debt.domain.Debt;
import com.moneylog.core.debt.exception.DebtExceptions.DebtNotFoundException;
import com.moneylog.core.finance.app.gateways.LedgerWriteGateway;
import com.moneylog.core.sk.ledger.PostingSourceType;
import com.moneylog.core.sk.persistence.Versioned;
import com.moneylog.core.sk.time.TimeProvider;

public class CancelDebtUseCaseImpl implements CancelDebtUseCase {

    private final PersonGateway people;
    private final DebtGateway debts;
    private final CategoryGateway categories;
    private final LedgerWriteGateway ledger;
    private final TimeProvider time;

    @SuppressWarnings("java:S107")
    public CancelDebtUseCaseImpl(PersonGateway people, DebtGateway debts, CategoryGateway categories, LedgerWriteGateway ledger,
                                 TimeProvider time) {
        this.people = people;
        this.debts = debts;
        this.categories = categories;
        this.ledger = ledger;
        this.time = time;
    }

    @Override
    public DebtView handle(CancelDebtCommand command) {
        // quitar e perdoar a mesma dívida se serializam: o saldo que o perdão zera é o lido sob a trava
        debts.lockForUpdate(command.userId(), command.debtId());
        Versioned<Debt> current = debts.findById(command.userId(), command.debtId())
                .orElseThrow(() -> new DebtNotFoundException(command.debtId()));
        Debt debt = current.value();
        debt.cancel(command.reason(), time.today());
        Versioned<Debt> saved = debts.update(debt, current.version());
        ledger.append(command.userId(), debt.entries().stream()
                .filter(e -> e.active() && e.source().type() == PostingSourceType.DEBT_CANCELLATION).toList());
        return DebtViews.one(people, debts, categories, command.userId(), saved, time.today());
    }
}
