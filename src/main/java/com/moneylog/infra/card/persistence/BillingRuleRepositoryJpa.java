package com.moneylog.infra.card.persistence;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BillingRuleRepositoryJpa extends JpaRepository<BillingRuleEntity, UUID> {

    List<BillingRuleEntity> findByUserIdAndCardId(UUID userId, UUID cardId);

    List<BillingRuleEntity> findByUserIdAndCardIdIn(UUID userId, Collection<UUID> cardIds);

    List<BillingRuleEntity> findByUserId(UUID userId);

    /** Os dias da regra mais recente, por projeção escalar: não carrega entidade, então não deixa estado velho no contexto. */
    interface DaysView {
        int getClosingDay();

        int getDueDay();
    }

    @Query("""
            select r.closingDay as closingDay, r.dueDay as dueDay from BillingRuleEntity r
            where r.userId = :userId and r.cardId = :cardId order by r.effectiveFrom desc""")
    List<DaysView> latestDays(@Param("userId") UUID userId, @Param("cardId") UUID cardId, Pageable page);
}
