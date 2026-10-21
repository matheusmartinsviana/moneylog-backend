package com.moneylog.core.invitation.app.gateways;

import java.util.Optional;
import java.util.UUID;

public interface InviterNameGateway {

    Optional<String> findDisplayName(UUID userId);
}
