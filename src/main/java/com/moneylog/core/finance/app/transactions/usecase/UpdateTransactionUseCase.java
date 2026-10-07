package com.moneylog.core.finance.app.transactions.usecase;

import java.util.UUID;

import com.moneylog.core.finance.app.transactions.TransactionDetails;

public interface UpdateTransactionUseCase {

    /** Motivo gravado nos lançamentos desativados pela edição. */
    String EDIT_REASON = "EDIT";

    /**
     * Edita a movimentação (S-68, S-81): grava a revisão com o estado anterior, troca a movimentação e desativa os
     * lançamentos antigos (motivo {@code EDIT}) gravando os novos, tudo na mesma transação.
     *
     * @throws com.moneylog.core.sk.exception.VersionConflictException                     versão desatualizada
     * @throws com.moneylog.core.transaction.exception.TransactionExceptions.TransactionNotEditableException estornada ou {@code type} diferente
     */
    TransactionDetails handle(UpdateTransactionCommand command);

    /** {@code body} é o corpo do tipo da movimentação (os mesmos comandos da criação). */
    record UpdateTransactionCommand(UUID transactionId, long version, CreateTransactionCommand body) {
    }
}
