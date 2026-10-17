package com.moneylog.core.finance.app.export;

import java.util.UUID;

public interface RestoreTargetGateway {

    void lockUser(UUID userId);

    boolean hasData(UUID userId);
}
