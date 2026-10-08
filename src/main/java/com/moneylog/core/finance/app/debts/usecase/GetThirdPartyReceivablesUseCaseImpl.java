package com.moneylog.core.finance.app.debts.usecase;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.moneylog.core.debt.app.gateways.PersonGateway;
import com.moneylog.core.finance.app.gateways.FinancialRecordsGateway;
import com.moneylog.core.finance.domain.FinancialRecords;
import com.moneylog.core.finance.domain.ThirdPartyReceivables;
import com.moneylog.core.sk.time.Competence;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.core.thirdparty.app.gateways.ThirdPartyExpenseGateway;
import com.moneylog.core.thirdparty.domain.ThirdPartyExpense;

public class GetThirdPartyReceivablesUseCaseImpl implements GetThirdPartyReceivablesUseCase {

    private final FinancialRecordsGateway records;
    private final ThirdPartyExpenseGateway marks;
    private final PersonGateway people;
    private final TimeProvider time;

    public GetThirdPartyReceivablesUseCaseImpl(FinancialRecordsGateway records, ThirdPartyExpenseGateway marks, PersonGateway people,
                                               TimeProvider time) {
        this.records = records;
        this.marks = marks;
        this.people = people;
        this.time = time;
    }

    @Override
    public ThirdPartyReceivables.Overview handle(GetThirdPartyReceivablesQuery query) {
        FinancialRecords loaded = records.load(query.userId());
        List<ThirdPartyExpense> expenses = marks.findAll(query.userId());
        java.util.Set<UUID> ids = new java.util.HashSet<>();
        loaded.debts().forEach(d -> {
            if (d.personId() != null) {
                ids.add(d.personId());
            }
        });
        expenses.forEach(e -> ids.add(e.personId()));
        Map<UUID, String> names = new HashMap<>();
        people.findByIds(query.userId(), ids).forEach((id, person) -> names.put(id, person.name()));
        Competence competence = query.competence() == null ? Competence.from(time.today()) : query.competence();
        return ThirdPartyReceivables.of(loaded, expenses, names, time.today(), competence);
    }
}
