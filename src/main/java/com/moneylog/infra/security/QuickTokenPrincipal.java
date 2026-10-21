package com.moneylog.infra.security;

import java.util.UUID;

public record QuickTokenPrincipal(UUID userId, UUID tokenId, UUID accountId) {
}
