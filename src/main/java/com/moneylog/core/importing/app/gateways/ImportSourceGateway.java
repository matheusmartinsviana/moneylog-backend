package com.moneylog.core.importing.app.gateways;

import java.util.UUID;

/** Posse da conta ou do cartão do extrato (id de outro usuário não existe: 404). */
public interface ImportSourceGateway {

    boolean accountExists(UUID userId, UUID accountId);

    boolean cardExists(UUID userId, UUID cardId);
}
