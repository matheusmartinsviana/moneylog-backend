package com.moneylog.core.transaction.app.gateways;

import java.time.Instant;
import java.util.UUID;

import com.moneylog.core.transaction.domain.Transaction;

/**
 * Revisões de movimentação (D1 §2.4 regra 2, S-68): o estado <b>anterior</b> de cada edição, guardado antes de a
 * movimentação e os lançamentos serem trocados. Todo método filtra pelo {@code userId}.
 */
public interface TransactionRevisionGateway {

    /**
     * Grava o estado de {@code previous} (campos da movimentação; os lançamentos já ficam inativos no razão) como a
     * revisão {@code revision}, única por movimentação.
     */
    void save(UUID userId, Transaction previous, int revision, Instant at);

    /**
     * Como {@link #save(UUID, Transaction, int, Instant)}, registrando também o que motivou a revisão: {@code change}
     * (ex.: {@code CANCEL_INSTALLMENTS}) e o motivo informado pelo usuário. É onde o motivo do cancelamento das parcelas
     * restantes fica guardado (a linha da movimentação não tem coluna para ele).
     */
    void save(UUID userId, Transaction previous, int revision, Instant at, String change, String reason);
}
