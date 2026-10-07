package com.moneylog.core.finance.app.cards.usecase;

import java.util.UUID;

import com.moneylog.core.finance.app.cards.CardDetails;
import com.moneylog.core.sk.money.Money;

public interface UpdateCardDetailsUseCase {

    /**
     * Muda nome, instituição, limite e conta de pagamento padrão. Dias de fechamento/vencimento diferentes dos atuais são a
     * mudança de dias (S-42, S-128): o caso de uso delega a {@link ChangeCardBillingDaysUseCase}, que aplica tudo (detalhes
     * e dias) na mesma transação e exige o {@code confirmedEffectiveFrom} da prévia.
     *
     * @throws com.moneylog.core.sk.exception.VersionConflictException versão desatualizada
     */
    CardDetails handle(UpdateCardDetailsCommand command);

    record UpdateCardDetailsCommand(UUID userId, UUID cardId, String name, String institution, Money limit, int closingDay,
                                    int dueDay, UUID defaultPaymentAccountId, java.time.LocalDate confirmedEffectiveFrom, long version) {

        /** Sem mudança de dias: nada a confirmar. */
        public UpdateCardDetailsCommand(UUID userId, UUID cardId, String name, String institution, Money limit, int closingDay,
                                        int dueDay, UUID defaultPaymentAccountId, long version) {
            this(userId, cardId, name, institution, limit, closingDay, dueDay, defaultPaymentAccountId, null, version);
        }
    }
}
