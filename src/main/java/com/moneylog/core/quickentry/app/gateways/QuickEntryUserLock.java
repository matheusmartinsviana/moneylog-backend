package com.moneylog.core.quickentry.app.gateways;

import java.util.UUID;

public interface QuickEntryUserLock {

    void lock(UUID userId);
}
