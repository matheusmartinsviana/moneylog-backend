package com.moneylog.infra.income.gateways;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import com.moneylog.core.income.app.gateways.IncomeProfileGateway;
import com.moneylog.core.income.domain.IncomeProfile;
import com.moneylog.core.income.domain.PayrollKind;
import com.moneylog.core.income.exception.IncomeExceptions.IncomeProfileNotFoundException;
import com.moneylog.core.sk.exception.VersionConflictException;
import com.moneylog.core.sk.id.IdGenerator;
import com.moneylog.core.sk.page.PageQuery;
import com.moneylog.core.sk.page.Slice;
import com.moneylog.core.sk.persistence.Versioned;
import com.moneylog.core.sk.time.Competence;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.infra.income.persistence.IncomeDeductionEntity;
import com.moneylog.infra.income.persistence.IncomeDeductionRepositoryJpa;
import com.moneylog.infra.income.persistence.IncomeOccurrenceEntity;
import com.moneylog.infra.income.persistence.IncomeOccurrenceRepositoryJpa;
import com.moneylog.infra.income.persistence.IncomeProfileEntity;
import com.moneylog.infra.income.persistence.IncomeProfileRepositoryJpa;
import com.moneylog.infra.persistence.OffsetLimit;

public class IncomeProfileGatewayImpl implements IncomeProfileGateway {

    private final IncomeProfileRepositoryJpa profiles;
    private final IncomeDeductionRepositoryJpa deductions;
    private final IncomeOccurrenceRepositoryJpa occurrences;
    private final IdGenerator ids;
    private final TimeProvider time;

    public IncomeProfileGatewayImpl(IncomeProfileRepositoryJpa profiles, IncomeDeductionRepositoryJpa deductions,
                                    IncomeOccurrenceRepositoryJpa occurrences, IdGenerator ids, TimeProvider time) {
        this.profiles = profiles;
        this.deductions = deductions;
        this.occurrences = occurrences;
        this.ids = ids;
        this.time = time;
    }

    @Override
    public Versioned<IncomeProfile> insert(IncomeProfile profile) {
        IncomeProfileEntity entity = new IncomeProfileEntity(profile.id(), profile.userId(), profile.source(), profile.gross().cents(),
                profile.gross().currency().value(), profile.dependents(),
                profile.advance() == null ? null : profile.advance().dayOfMonth(),
                profile.advance() == null ? null : profile.advance().percentBps(), profile.balanceDayOfMonth(),
                profile.thirteenth().enabled(), profile.thirteenth().firstInstallmentMonth(), profile.isArchived(), time.now());
        entity.applyProvenance(profile.provenance());
        IncomeProfileEntity saved = profiles.saveAndFlush(entity);
        List<IncomeDeductionEntity> rows = deductions.saveAllAndFlush(IncomeProfileRepositoryMapper.deductionRows(profile, ids::next));
        return IncomeProfileRepositoryMapper.toVersioned(saved, rows);
    }

    @Override
    public Versioned<IncomeProfile> update(IncomeProfile profile, long expectedVersion) {
        IncomeProfileEntity entity = profiles.findByIdAndUserId(profile.id(), profile.userId())
                .orElseThrow(() -> new IncomeProfileNotFoundException(profile.id()));
        if (entity.getVersion() != expectedVersion) {
            throw new VersionConflictException(expectedVersion, entity.getVersion());
        }
        Instant now = time.now();
        entity.apply(profile.source(), profile.gross().cents(), profile.dependents(),
                profile.advance() == null ? null : profile.advance().dayOfMonth(),
                profile.advance() == null ? null : profile.advance().percentBps(), profile.balanceDayOfMonth(),
                profile.thirteenth().enabled(), profile.thirteenth().firstInstallmentMonth(), profile.isArchived(), now);
        entity.applyProvenance(profile.provenance());
        IncomeProfileEntity saved = profiles.saveAndFlush(entity);
        deductions.deleteAllOf(profile.userId(), profile.id());
        List<IncomeDeductionEntity> rows = deductions.saveAllAndFlush(IncomeProfileRepositoryMapper.deductionRows(profile, ids::next));
        return IncomeProfileRepositoryMapper.toVersioned(saved, rows);
    }

    @Override
    public void lockForUpdate(UUID userId, UUID id) {
        profiles.lockByIdAndUserId(id, userId);
    }

    @Override
    public Optional<Versioned<IncomeProfile>> findById(UUID userId, UUID id) {
        return profiles.findByIdAndUserId(id, userId).map(entity -> hydrate(userId, List.of(entity)).get(0));
    }

    @Override
    public Slice<Versioned<IncomeProfile>> list(UUID userId, boolean archived, PageQuery page) {
        if (page.isBeyondAnyData()) {
            return new Slice<>(List.of(), false);
        }
        return Slice.fromOverfetch(hydrate(userId, profiles.findPage(userId, archived, OffsetLimit.of(page))), page);
    }

    @Override
    public Optional<UUID> findOccurrence(UUID userId, UUID profileId, Competence competence, PayrollKind kind) {
        return occurrences.find(userId, profileId, competence.toString(), kind).map(IncomeOccurrenceEntity::getRecurrenceId);
    }

    @Override
    public void linkOccurrence(UUID userId, UUID profileId, Competence competence, PayrollKind kind, UUID recurrenceId) {
        occurrences.saveAndFlush(new IncomeOccurrenceEntity(ids.next(), userId, profileId, competence.toString(), kind, recurrenceId,
                time.now()));
    }

    @Override
    public List<OccurrenceLink> occurrenceLinks(UUID userId) {
        return occurrences.findByUserId(userId).stream()
                .map(o -> new OccurrenceLink(o.getProfileId(), Competence.parse(o.getCompetence()), o.getKind(), o.getRecurrenceId()))
                .toList();
    }

    @Override
    public List<IncomeProfile> activeProfiles(UUID userId) {
        return hydrate(userId, profiles.findByUserIdAndArchivedFalse(userId)).stream().map(Versioned::value).toList();
    }

    @Override
    public List<IncomeProfile> findAll(UUID userId) {
        return hydrate(userId, profiles.findByUserId(userId)).stream().map(Versioned::value).toList();
    }

    private List<Versioned<IncomeProfile>> hydrate(UUID userId, List<IncomeProfileEntity> entities) {
        if (entities.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<IncomeDeductionEntity>> byProfile = deductions
                .findByUserIdAndProfileIdInOrderByProfileIdAscPositionAsc(userId, entities.stream().map(IncomeProfileEntity::getId).toList())
                .stream().collect(Collectors.groupingBy(IncomeDeductionEntity::getProfileId));
        return entities.stream()
                .map(e -> IncomeProfileRepositoryMapper.toVersioned(e, byProfile.getOrDefault(e.getId(), List.of())))
                .toList();
    }
}
