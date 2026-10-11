package com.moneylog.core.auth.app.usecase;

import java.time.Duration;

public interface PrepareFirstAccessUseCase {

    FirstAccessPrepared handle(PrepareFirstAccessCommand command);

    record PrepareFirstAccessCommand(String providedCode, Duration ttl) {
    }

    record FirstAccessPrepared(boolean setupRequired, String generatedCode) {
    }
}
