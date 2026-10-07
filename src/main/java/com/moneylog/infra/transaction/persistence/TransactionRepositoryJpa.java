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

    /** {@code SELECT ... FOR UPDATE} da linha (B1). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from TransactionEntity t where t.id = :id and t.userId = :userId")
    Optional<TransactionEntity> lockByIdAndUserId(@Param("id") UUID id, @Param("userId") UUID userId);

    /**
     * {@code SELECT id ... FOR UPDATE} de todas as movimentações do cartão, em ordem de id: a primeira trava da mudança de
     * dias de fechamento/vencimento (movimentações → cartão → contas). Só ids, para não encher o contexto de entidades
     * antes de elas serem lidas já travadas.
     */
    @Query(value = "select id from transactions where user_id = :userId and card_id = :cardId order by id for update", nativeQuery = true)
    List<UUID> lockIdsOfCard(@Param("userId") UUID userId, @Param("cardId") UUID cardId);

    List<TransactionEntity> findByUserId(UUID userId);

    /** Todas as movimentações de um cartão (ativas e estornadas): a cadeia de faturas do cartão (S-135). */
    List<TransactionEntity> findByUserIdAndCardId(UUID userId, UUID cardId);

    /** Σ (centavos) dos reembolsos ativos ligados à despesa. */
    @Query("""
            select coalesce(sum(t.amountCents), 0) from TransactionEntity t
            where t.userId = :userId and t.refundOfId = :originalId and t.type = com.moneylog.core.transaction.domain.TransactionType.REFUND
              and t.status = com.moneylog.core.transaction.domain.TransactionStatus.ACTIVE""")
    long sumActiveRefundsCents(@Param("userId") UUID userId, @Param("originalId") UUID originalId);

    /** As movimentações ligadas às dívidas (origem e quitações, ativas e estornadas): a quitação de cada dívida (S-41). */
    @Query("""
            select t.id as id, t.debtId as debtId, t.amountCents as amountCents, t.status as status, t.transactionDate as date
            from TransactionEntity t where t.userId = :userId and t.debtId in :debtIds""")
    List<DebtLinkRow> findDebtLinks(@Param("userId") UUID userId, @Param("debtIds") java.util.Collection<UUID> debtIds);

    /** Todas as movimentações de dívida do usuário (carregamento único). */
    @Query("""
            select t.id as id, t.debtId as debtId, t.amountCents as amountCents, t.status as status, t.transactionDate as date
            from TransactionEntity t where t.userId = :userId and t.debtId is not null""")
    List<DebtLinkRow> findAllDebtLinks(@Param("userId") UUID userId);

    /** Linha mínima de uma movimentação ligada a uma dívida. */
    interface DebtLinkRow {
        UUID getId();

        UUID getDebtId();

        long getAmountCents();

        com.moneylog.core.transaction.domain.TransactionStatus getStatus();

        java.time.LocalDate getDate();
    }

    /** As ocorrências realizadas: movimentações <b>ativas</b> com origem em recorrência (S-49: estornar reabre a ocorrência). */
    @Query("""
            select t.id as id, t.recurrenceId as recurrenceId, t.occurrenceDate as date, t.amountCents as amountCents
            from TransactionEntity t
            where t.userId = :userId and t.recurrenceId in :recurrenceIds
              and t.status = com.moneylog.core.transaction.domain.TransactionStatus.ACTIVE""")
    List<RealizedRow> findActiveRealized(@Param("userId") UUID userId, @Param("recurrenceIds") java.util.Collection<UUID> recurrenceIds);

    /** Todas as ocorrências realizadas do usuário (movimentações ativas com origem em recorrência). */
    @Query("""
            select t.id as id, t.recurrenceId as recurrenceId, t.occurrenceDate as date, t.amountCents as amountCents
            from TransactionEntity t
            where t.userId = :userId and t.recurrenceId is not null
              and t.status = com.moneylog.core.transaction.domain.TransactionStatus.ACTIVE""")
    List<RealizedRow> findAllActiveRealized(@Param("userId") UUID userId);

    /** Linha mínima de uma ocorrência realizada. */
    interface RealizedRow {
        UUID getId();

        UUID getRecurrenceId();

        java.time.LocalDate getDate();

        long getAmountCents();
    }
}
