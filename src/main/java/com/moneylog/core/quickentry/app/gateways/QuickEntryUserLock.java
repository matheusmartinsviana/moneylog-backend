package com.moneylog.core.quickentry.app.gateways;

import java.util.UUID;

public interface QuickEntryUserLock {

    boolean lock(UUID userId);
}
