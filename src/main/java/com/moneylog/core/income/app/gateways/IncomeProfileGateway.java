package com.moneylog.core.income.app.gateways;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.moneylog.core.income.domain.IncomeProfile;
import com.moneylog.core.income.domain.PayrollKind;
import com.moneylog.core.sk.page.PageQuery;
import com.moneylog.core.sk.page.Slice;
import com.moneylog.core.sk.persistence.Versioned;
import com.moneylog.core.sk.time.Competence;

public interface IncomeProfileGateway {

    record OccurrenceLink(UUID profileId, Competence competence, PayrollKind kind, UUID recurrenceId) {
    }

    Versioned<IncomeProfile> insert(IncomeProfile profile);

    Versioned<IncomeProfile> update(IncomeProfile profile, long expectedVersion);

    void lockForUpdate(UUID userId, UUID id);

    Optional<Versioned<IncomeProfile>> findById(UUID userId, UUID id);

    Slice<Versioned<IncomeProfile>> list(UUID userId, boolean archived, PageQuery page);

    Optional<UUID> findOccurrence(UUID userId, UUID profileId, Competence competence, PayrollKind kind);

    void linkOccurrence(UUID userId, UUID profileId, Competence competence, PayrollKind kind, UUID recurrenceId);

    List<OccurrenceLink> occurrenceLinks(UUID userId);

    List<IncomeProfile> activeProfiles(UUID userId);

    List<IncomeProfile> findAll(UUID userId);
}
