package com.moneylog.core.quickentry.app;

public interface CreateQuickEntryUseCase {

    String IDEMPOTENCY_SCOPE = "POST /v1/quick-entries";

    CreateQuickEntryResult handle(CreateQuickEntryCommand command);
}
