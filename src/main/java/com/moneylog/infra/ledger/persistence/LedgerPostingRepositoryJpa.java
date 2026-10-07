package com.moneylog.infra.ledger.persistence;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.moneylog.core.sk.ledger.PostingSourceType;

public interface LedgerPostingRepositoryJpa extends JpaRepository<LedgerPostingEntity, UUID> {

    /** Carregamento único (S-79): usa um índice parcial {@code WHERE active} (T24 CA4). */
    @Query("""
            select p from LedgerPostingEntity p
            where p.userId = :userId and p.active = true
            order by p.effectiveDate, p.createdAt, p.id""")
    List<LedgerPostingEntity> findActive(@Param("userId") UUID userId);

    @Query("""
            select p from LedgerPostingEntity p
            where p.userId = :userId and p.transactionId in :transactionIds
            order by p.effectiveDate, p.createdAt, p.id""")
    List<LedgerPostingEntity> findByTransactions(@Param("userId") UUID userId,
                                                 @Param("transactionIds") Collection<UUID> transactionIds);

    /** Todos os lançamentos (ativos e inativos) de movimentações do usuário, para reidratar as movimentações. */
    @Query("""
            select p from LedgerPostingEntity p
            where p.userId = :userId and p.transactionId is not null
            order by p.effectiveDate, p.createdAt, p.id""")
    List<LedgerPostingEntity> findAllOfTransactions(@Param("userId") UUID userId);

    /**
     * Uma única instrução SQL desativa a origem inteira. Faz flush antes e limpa o contexto depois: sem isso, uma leitura
     * na mesma transação devolveria os lançamentos ainda como ativos (instâncias gerenciadas desatualizadas).
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update LedgerPostingEntity p
            set p.active = false, p.deactivatedAt = :at, p.deactivationReason = :reason, p.updatedAt = :at
            where p.userId = :userId and p.sourceType = :sourceType and p.sourceId = :sourceId and p.active = true""")
    int deactivateBySource(@Param("userId") UUID userId, @Param("sourceType") PostingSourceType sourceType,
                           @Param("sourceId") UUID sourceId, @Param("reason") String reason, @Param("at") Instant at);

    /** Desativa, numa instrução, os lançamentos ativos das parcelas de uma movimentação (cancelamento de parcelas). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update LedgerPostingEntity p
            set p.active = false, p.deactivatedAt = :at, p.deactivationReason = :reason, p.updatedAt = :at
            where p.userId = :userId and p.sourceType = com.moneylog.core.sk.ledger.PostingSourceType.TRANSACTION
              and p.sourceId = :transactionId and p.installmentId in :installmentIds and p.active = true""")
    int deactivateByInstallments(@Param("userId") UUID userId, @Param("transactionId") UUID transactionId,
                                 @Param("installmentIds") Collection<UUID> installmentIds, @Param("reason") String reason,
                                 @Param("at") Instant at);

    /** Os lançamentos próprios das dívidas (reconhecimento e perdão, ativos e inativos), para reidratá-las. */
    @Query("""
            select p from LedgerPostingEntity p
            where p.userId = :userId and p.sourceType in :types and p.sourceId in :sourceIds
            order by p.effectiveDate, p.createdAt, p.id""")
    List<LedgerPostingEntity> findBySources(@Param("userId") UUID userId, @Param("types") Collection<PostingSourceType> types,
                                            @Param("sourceIds") Collection<UUID> sourceIds);

    /** Os lançamentos próprios de todas as dívidas do usuário (carregamento único). */
    @Query("""
            select p from LedgerPostingEntity p
            where p.userId = :userId and p.sourceType in :types
            order by p.effectiveDate, p.createdAt, p.id""")
    List<LedgerPostingEntity> findAllOfSourceTypes(@Param("userId") UUID userId, @Param("types") Collection<PostingSourceType> types);
}
