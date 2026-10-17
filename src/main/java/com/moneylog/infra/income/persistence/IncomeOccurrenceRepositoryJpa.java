package com.moneylog.infra.income.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.moneylog.core.income.domain.PayrollKind;

public interface IncomeOccurrenceRepositoryJpa extends JpaRepository<IncomeOccurrenceEntity, UUID> {

    @Query("""
            select o from IncomeOccurrenceEntity o
            where o.userId = :userId and o.profileId = :profileId and o.competence = :competence and o.kind = :kind""")
    Optional<IncomeOccurrenceEntity> find(@Param("userId") UUID userId, @Param("profileId") UUID profileId,
                                          @Param("competence") String competence, @Param("kind") PayrollKind kind);

    List<IncomeOccurrenceEntity> findByUserId(UUID userId);
}
