package com.moneylog.core.importing.app.gateways;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.moneylog.core.importing.domain.ExistingEntry;
import com.moneylog.core.importing.domain.ImportDirection;
import com.moneylog.core.sk.money.Money;

public interface ExistingEntriesGateway {

    List<ExistingEntry> findForSource(UUID userId, UUID sourceId, boolean card, LocalDate from, LocalDate to);

    List<ExistingEntry> findInOtherAccounts(UUID userId, UUID excludingAccountId, LocalDate from, LocalDate to);

    boolean hasActiveOppositeLeg(UUID userId, UUID accountId, ImportDirection direction, Money amount, LocalDate date);
}
