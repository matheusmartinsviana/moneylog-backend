package com.moneylog.core.finance.app.gateways;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.moneylog.core.sk.ledger.LedgerEntry;
import com.moneylog.core.sk.ledger.PostingSourceType;

/**
 * Escrita do razão (D1 §1.1, ADR-002): grava os lançamentos de um grupo e desativa por origem. Posting nunca é editado
 * nem reativado; corrigir é desativar o grupo e gravar um novo.
 */
public interface LedgerWriteGateway {

    /**
     * Grava lançamentos ativos. Cada grupo (mesma origem) precisa somar zero: se algum não fechar, nada é gravado e a
     * exceção sai antes de qualquer SQL.
     *
     * @throws com.moneylog.core.sk.ledger.LedgerExceptions.UnbalancedPostingsException grupo que não soma zero
     */
    void append(UUID userId, List<LedgerEntry> entries);

    /**
     * Desativa, numa única instrução, todos os lançamentos ativos da origem (estorno, correção do saldo inicial,
     * edição).
     *
     * @param reason motivo estável ({@code VOID}, {@code EDIT}, ...)
     * @return quantos lançamentos foram desativados
     */
    int deactivateBySource(UUID userId, PostingSourceType sourceType, UUID sourceId, String reason, Instant at);

    /**
     * Desativa os lançamentos <b>ativos</b> das parcelas indicadas de uma compra no cartão (cancelamento das parcelas
     * restantes, D1 §2.5.2): só os grupos das parcelas, os das demais ficam ativos.
     *
     * @return quantos lançamentos foram desativados
     */
    int deactivateByInstallments(UUID userId, UUID transactionId, java.util.Collection<UUID> installmentIds, String reason, Instant at);
}
