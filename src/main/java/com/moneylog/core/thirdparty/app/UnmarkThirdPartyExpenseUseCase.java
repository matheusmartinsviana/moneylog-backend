package com.moneylog.core.thirdparty.app;

import java.util.UUID;

public interface UnmarkThirdPartyExpenseUseCase {

    void handle(UnmarkThirdPartyExpenseCommand command);

    void deleteReceipt(UnmarkThirdPartyExpenseCommand command);

    record UnmarkThirdPartyExpenseCommand(UUID userId, UUID id) {
    }
}
