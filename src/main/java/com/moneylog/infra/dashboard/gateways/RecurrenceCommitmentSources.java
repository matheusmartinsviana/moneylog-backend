package com.moneylog.infra.dashboard.gateways;

import java.util.Collection;
import java.util.UUID;

import com.moneylog.core.finance.app.gateways.CommitmentSourcesGateway;
import com.moneylog.core.recurrence.app.gateways.RecurrenceGateway;
import com.moneylog.core.recurrence.domain.RecurringTransaction;

public class RecurrenceCommitmentSources implements CommitmentSourcesGateway {

    private final RecurrenceGateway recurrences;

    public RecurrenceCommitmentSources(RecurrenceGateway recurrences) {
        this.recurrences = recurrences;
    }

    @Override
    public Collection<RecurringTransaction> recurrences(UUID userId) {
        return recurrences.findAll(userId);
    }
}
