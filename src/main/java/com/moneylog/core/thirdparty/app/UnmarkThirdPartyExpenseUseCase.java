package com.moneylog.core.thirdparty.app;

import java.util.UUID;

public interface UnmarkThirdPartyExpenseUseCase {

    /**
     * Desfaz a marca de despesa de terceiro (correção de responsável). Só a marca sai: a despesa, o consumo e os reembolsos ficam como
     * estão. Marca inexistente ou de outro usuário: {@code 404 THIRD_PARTY_EXPENSE_NOT_FOUND}.
     */
    void handle(UnmarkThirdPartyExpenseCommand command);

    record UnmarkThirdPartyExpenseCommand(UUID userId, UUID id) {
    }
}
