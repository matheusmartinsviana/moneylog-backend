package com.moneylog.core.finance.app.gateways;

import java.util.Collection;
import java.util.UUID;

import com.moneylog.core.recurrence.domain.RecurringTransaction;

public interface CommitmentSourcesGateway {

    Collection<RecurringTransaction> recurrences(UUID userId);
}
