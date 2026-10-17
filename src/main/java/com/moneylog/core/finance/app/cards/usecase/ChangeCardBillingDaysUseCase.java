package com.moneylog.core.finance.app.cards.usecase;

import java.time.LocalDate;
import java.util.UUID;

import com.moneylog.core.card.domain.CardProfile;
import com.moneylog.core.finance.app.cards.CardDetails;
import com.moneylog.core.sk.money.Money;

public interface ChangeCardBillingDaysUseCase {

    CardDetails handle(ChangeCardBillingDaysCommand command);

    record ChangeCardBillingDaysCommand(UUID userId, UUID cardId, String name, String institution, Money limit, int closingDay, int dueDay,
                                        UUID defaultPaymentAccountId, LocalDate confirmedEffectiveFrom, long version, CardProfile profile) {

        public ChangeCardBillingDaysCommand {
            profile = profile == null ? CardProfile.EMPTY : profile;
        }

        public ChangeCardBillingDaysCommand(UUID userId, UUID cardId, String name, String institution, Money limit, int closingDay, int dueDay,
                                            UUID defaultPaymentAccountId, LocalDate confirmedEffectiveFrom, long version) {
            this(userId, cardId, name, institution, limit, closingDay, dueDay, defaultPaymentAccountId, confirmedEffectiveFrom, version, null);
        }
    }
}
