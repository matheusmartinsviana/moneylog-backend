package com.moneylog.core.finance.app.transactions.usecase;

import java.util.UUID;

import com.moneylog.core.finance.app.transactions.TransactionDetails;

public interface CancelInstallmentsUseCase {

    /** Motivo gravado nos lançamentos desativados pelo cancelamento das parcelas. */
    String CANCEL_REASON = "CANCEL";

    /** Rótulo do cancelamento na revisão (onde o motivo informado fica guardado). */
    String REVISION_CHANGE = "CANCEL_INSTALLMENTS";

    /**
     * Cancela as parcelas restantes de uma compra no cartão (D1 §2.5.2, S-122): as de faturas {@code FUTURE}/{@code OPEN}
     * viram {@code CANCELED} (deixam de gerar compromisso e devolvem o limite) e só os lançamentos delas ficam inativos; as
     * de faturas fechadas não mudam (a correção é um reembolso no cartão). O motivo fica na revisão da movimentação.
     *
     * @throws com.moneylog.core.transaction.exception.TransactionExceptions.TransactionAlreadyVoidedException compra estornada
     * @throws com.moneylog.core.transaction.exception.TransactionExceptions.InvalidTransactionOperationException
     *         {@code TRANSACTION_NOT_CANCELABLE}: a movimentação não é uma compra no cartão
     * @throws com.moneylog.core.card.exception.CardExceptions.NoInstallmentsToCancelException nenhuma parcela cancelável
     */
    TransactionDetails handle(CancelInstallmentsCommand command);

    record CancelInstallmentsCommand(UUID userId, UUID transactionId, String reason) {
    }
}
