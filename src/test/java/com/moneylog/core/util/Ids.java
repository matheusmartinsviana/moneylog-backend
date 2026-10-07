package com.moneylog.core.util;

import java.util.UUID;

/**
 * UUIDs fixos e legíveis para os testes (BE 07: nada de {@code UUID.randomUUID()} em factory).
 */
public final class Ids {

    public static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    public static final UUID OTHER_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private Ids() {
    }
}
