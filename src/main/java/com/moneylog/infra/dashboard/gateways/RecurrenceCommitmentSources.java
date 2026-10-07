package com.moneylog.infra.dashboard.gateways;

import java.util.Collection;
import java.util.UUID;

import com.moneylog.core.finance.app.gateways.CommitmentSourcesGateway;
import com.moneylog.core.recurrence.app.gateways.RecurrenceGateway;
import com.moneylog.core.recurrence.domain.RecurringTransaction;

/**
 * As recorrências do usuário como fonte de compromissos (S-64, T79): ocorrências {@code PENDING} (nem realizadas nem puladas) das
 * recorrências ativas; a desativada ou encerrada não gera ocorrência. Substitui {@code EmptyCommitmentSources}. As faturas e as
 * dívidas entram pelos {@code FinancialRecords}, não por aqui.
 */
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
