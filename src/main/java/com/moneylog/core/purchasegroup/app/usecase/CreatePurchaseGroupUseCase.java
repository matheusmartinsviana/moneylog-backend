package com.moneylog.core.purchasegroup.app.usecase;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.moneylog.core.purchasegroup.app.PurchaseGroupView;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.transaction.domain.ShareLine;
import com.moneylog.core.transaction.domain.SplitMode;

public interface CreatePurchaseGroupUseCase {

    String IDEMPOTENCY_SCOPE = "POST /v1/purchase-groups";

    PurchaseGroupView handle(CreatePurchaseGroupCommand command);

    record MemberSpec(UUID cardId, int totalInstallments, Money installmentAmount, Integer remainingInstallments,
                      LocalDate firstInvoiceDate, UUID categoryId, SplitMode splitMode, List<ShareLine> shares) {

        public MemberSpec {
            shares = shares == null ? List.of() : List.copyOf(shares);
        }
    }

    record CreatePurchaseGroupCommand(UUID userId, String name, boolean interest, Money declaredTotal, List<MemberSpec> members,
                                      UUID idempotencyKey) {

        public CreatePurchaseGroupCommand {
            members = members == null ? List.of() : List.copyOf(members);
        }
    }
}
