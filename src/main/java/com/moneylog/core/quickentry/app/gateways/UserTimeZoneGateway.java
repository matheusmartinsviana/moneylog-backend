package com.moneylog.core.quickentry.app.gateways;

import java.time.ZoneId;
import java.util.UUID;

public interface UserTimeZoneGateway {

    ZoneId timeZoneOf(UUID userId);
}
