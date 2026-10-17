package com.moneylog.core.finance.app.cards.usecase;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.moneylog.core.finance.app.cards.PurchasePreviewView;
import com.moneylog.core.recurrence.app.ForeignInput;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.transaction.domain.ShareLine;
import com.moneylog.core.transaction.domain.SplitMode;

public interface PreviewCardPurchaseUseCase {

    PurchasePreviewView handle(PreviewCardPurchaseQuery query);

    record PreviewCardPurchaseQuery(UUID userId, UUID cardId, Money amount, int installments, LocalDate date, SplitMode splitMode,
                                    List<ShareLine> shares, ForeignInput foreign) {

        public PreviewCardPurchaseQuery {
            shares = shares == null ? List.of() : List.copyOf(shares);
            foreign = foreign == null ? ForeignInput.NONE : foreign;
        }

        public PreviewCardPurchaseQuery(UUID userId, UUID cardId, Money amount, int installments, LocalDate date, SplitMode splitMode,
                                        List<ShareLine> shares) {
            this(userId, cardId, amount, installments, date, splitMode, shares, null);
        }

        public PreviewCardPurchaseQuery(UUID userId, UUID cardId, Money amount, int installments, LocalDate date) {
            this(userId, cardId, amount, installments, date, null, List.of());
        }
    }
}
