package com.moneylog.infra.transaction.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TransactionRepositoryJpa extends JpaRepository<TransactionEntity, UUID> {

    Optional<TransactionEntity> findByIdAndUserId(UUID id, UUID userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from TransactionEntity t where t.id = :id and t.userId = :userId")
    Optional<TransactionEntity> lockByIdAndUserId(@Param("id") UUID id, @Param("userId") UUID userId);

    @Query(value = "select id from transactions where user_id = :userId and card_id = :cardId order by id for update", nativeQuery = true)
    List<UUID> lockIdsOfCard(@Param("userId") UUID userId, @Param("cardId") UUID cardId);

    List<TransactionEntity> findByUserId(UUID userId);

    List<TransactionEntity> findByUserIdAndCardId(UUID userId, UUID cardId);

    List<TransactionEntity> findByUserIdAndRefundOfIdAndStatus(UUID userId, UUID refundOfId,
                                                               com.moneylog.core.transaction.domain.TransactionStatus status);

    @Query("""
            select coalesce(sum(t.amountCents), 0) from TransactionEntity t
            where t.userId = :userId and t.refundOfId = :originalId and t.type = com.moneylog.core.transaction.domain.TransactionType.REFUND
              and t.status = com.moneylog.core.transaction.domain.TransactionStatus.ACTIVE""")
    long sumActiveRefundsCents(@Param("userId") UUID userId, @Param("originalId") UUID originalId);

    @Query("""
            select t.id as id, t.debtId as debtId, coalesce(a.principalCents, t.amountCents) as amountCents, t.status as status,
                   t.transactionDate as date
            from TransactionEntity t left join PayrollLoanApplicationEntity a on a.transactionId = t.id
            where t.userId = :userId and t.debtId in :debtIds""")
    List<DebtLinkRow> findDebtLinks(@Param("userId") UUID userId, @Param("debtIds") java.util.Collection<UUID> debtIds);

    @Query("""
            select t.id as id, t.debtId as debtId, coalesce(a.principalCents, t.amountCents) as amountCents, t.status as status,
                   t.transactionDate as date
            from TransactionEntity t left join PayrollLoanApplicationEntity a on a.transactionId = t.id
            where t.userId = :userId and t.debtId is not null""")
    List<DebtLinkRow> findAllDebtLinks(@Param("userId") UUID userId);

    interface DebtLinkRow {
        UUID getId();

        UUID getDebtId();

        long getAmountCents();

        com.moneylog.core.transaction.domain.TransactionStatus getStatus();

        java.time.LocalDate getDate();
    }

    @Query("""
            select t.id as id, t.recurrenceId as recurrenceId, t.occurrenceDate as date, t.amountCents as amountCents
            from TransactionEntity t
            where t.userId = :userId and t.recurrenceId in :recurrenceIds
              and t.status = com.moneylog.core.transaction.domain.TransactionStatus.ACTIVE""")
    List<RealizedRow> findActiveRealized(@Param("userId") UUID userId, @Param("recurrenceIds") java.util.Collection<UUID> recurrenceIds);

    @Query("""
            select t.id as id, t.recurrenceId as recurrenceId, t.occurrenceDate as date, t.amountCents as amountCents
            from TransactionEntity t
            where t.userId = :userId and t.recurrenceId is not null
              and t.status = com.moneylog.core.transaction.domain.TransactionStatus.ACTIVE""")
    List<RealizedRow> findAllActiveRealized(@Param("userId") UUID userId);

    interface RealizedRow {
        UUID getId();

        UUID getRecurrenceId();

        java.time.LocalDate getDate();

        long getAmountCents();
    }
}
