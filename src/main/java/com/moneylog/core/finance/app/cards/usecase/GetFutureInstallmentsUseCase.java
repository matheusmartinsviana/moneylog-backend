package com.moneylog.core.finance.app.cards.usecase;

import java.util.UUID;

import com.moneylog.core.finance.app.cards.FutureInstallmentsView;

public interface GetFutureInstallmentsUseCase {

    /**
     * As parcelas não canceladas das faturas {@code FUTURE}, por fatura, com o total somado pelo backend (o mesmo
     * {@code Card.committedFuture}).
     *
     * @throws com.moneylog.core.card.exception.CardExceptions.CardNotFoundException cartão inexistente ou de outro usuário
     */
    FutureInstallmentsView handle(GetFutureInstallmentsQuery query);

    record GetFutureInstallmentsQuery(UUID userId, UUID cardId) {
    }
}
