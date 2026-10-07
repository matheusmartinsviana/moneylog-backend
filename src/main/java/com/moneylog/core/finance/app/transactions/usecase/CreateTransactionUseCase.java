package com.moneylog.core.finance.app.transactions.usecase;

import java.util.UUID;

import com.moneylog.core.finance.app.transactions.TransactionDetails;
import com.moneylog.core.transaction.domain.TransactionOrigin;

public interface CreateTransactionUseCase {

    /** Escopo da chave de idempotência (S-87): a rota. */
    String IDEMPOTENCY_SCOPE = "POST /v1/transactions";

    /**
     * Cria a movimentação e seus lançamentos na mesma transação. Só os tipos em conta (S-58); os demais respondem
     * {@code TRANSACTION_TYPE_NOT_AVAILABLE} sem gravar nada.
     *
     * @param idempotencyKey chave de idempotência (S-72, S-87). Gravada na mesma transação: a mesma chave com o mesmo
     *                       comando devolve o <b>estado atual</b> do recurso criado antes; com outro comando, 409
     *                       {@code IDEMPOTENCY_KEY_REUSED}; erros não são memorizados. {@code null} = sem idempotência
     *                       (uso interno e testes)
     */
    TransactionDetails handle(CreateTransactionCommand command, UUID idempotencyKey);

    /**
     * Cria a movimentação de uma linha de extrato importado (origem {@code IMPORT}, lote e chaves de deduplicação), pelos <b>mesmos</b> handlers e
     * lançamentos da criação manual (PR 01, regra 10). Sem idempotência própria: o lote já é idempotente e o índice único de {@code dedup_key} barra a
     * repetição. Roda na transação de quem chama.
     */
    TransactionDetails handleImported(CreateTransactionCommand command, TransactionOrigin origin);

    default TransactionDetails handle(CreateTransactionCommand command) {
        return handle(command, null);
    }
}
