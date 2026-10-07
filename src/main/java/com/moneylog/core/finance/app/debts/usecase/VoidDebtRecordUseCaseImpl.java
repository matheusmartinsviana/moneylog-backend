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

public class VoidDebtRecordUseCaseImpl implements VoidDebtRecordUseCase {

    /** Motivo gravado ao desativar os lançamentos do reconhecimento. */
    public static final String VOID_REASON = "VOID";

    private final PersonGateway people;
    private final DebtGateway debts;
    private final CategoryGateway categories;
    private final LedgerWriteGateway ledger;
    private final TimeProvider time;

    @SuppressWarnings("java:S107")
    public VoidDebtRecordUseCaseImpl(PersonGateway people, DebtGateway debts, CategoryGateway categories, LedgerWriteGateway ledger,
                                     TimeProvider time) {
        this.people = people;
        this.debts = debts;
        this.categories = categories;
        this.ledger = ledger;
        this.time = time;
    }

    @Override
    public DebtView handle(VoidDebtRecordCommand command) {
        debts.lockForUpdate(command.userId(), command.debtId());
        Versioned<Debt> current = debts.findById(command.userId(), command.debtId())
                .orElseThrow(() -> new DebtNotFoundException(command.debtId()));
        Debt debt = current.value();
        debt.voidRecord(command.reason(), time.today());
        Versioned<Debt> saved = debts.update(debt, current.version());
        ledger.deactivateBySource(command.userId(), PostingSourceType.DEBT_RECOGNITION, debt.id(), VOID_REASON, time.now());
        return DebtViews.one(people, debts, categories, command.userId(), saved, time.today());
    }
}
