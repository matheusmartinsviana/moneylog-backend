package com.moneylog.core.importing.app.gateways;

import java.util.UUID;

public interface ImportSourceGateway {

    boolean accountExists(UUID userId, UUID accountId);

    boolean cardExists(UUID userId, UUID cardId);
}
