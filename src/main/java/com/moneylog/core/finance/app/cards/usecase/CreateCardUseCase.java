package com.moneylog.core.finance.app.cards.usecase;

import java.util.UUID;

import com.moneylog.core.finance.app.cards.CardDetails;
import com.moneylog.core.sk.money.Money;

public interface CreateCardUseCase {

    /**
     * Cadastra um cartão ativo com a primeira regra de fechamento/vencimento (S-42). A conta de pagamento padrão, se
     * informada, precisa existir, estar ativa e ser {@code CASH}.
     */
    CardDetails handle(CreateCardCommand command);

    record CreateCardCommand(UUID userId, String name, String institution, Money limit, int closingDay, int dueDay,
                             UUID defaultPaymentAccountId) {
    }
}
