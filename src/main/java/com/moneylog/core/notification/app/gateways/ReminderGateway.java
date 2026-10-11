package com.moneylog.core.notification.app.gateways;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.moneylog.core.notification.domain.Reminder;
import com.moneylog.core.sk.page.PageQuery;
import com.moneylog.core.sk.page.Slice;

public interface ReminderGateway {

    Reminder save(Reminder reminder);

    Optional<Reminder> find(UUID userId, UUID id);

    Slice<Reminder> list(UUID userId, boolean includeDone, PageQuery page);

    List<Reminder> openUntil(UUID userId, LocalDate day);
}
