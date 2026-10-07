package com.moneylog.core.notification.app.gateways;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.moneylog.core.notification.domain.Reminder;
import com.moneylog.core.sk.page.PageQuery;
import com.moneylog.core.sk.page.Slice;

public interface ReminderGateway {

    /** Insere ou grava a mudança. */
    Reminder save(Reminder reminder);

    Optional<Reminder> find(UUID userId, UUID id);

    /** Abertos primeiro, por data; {@code includeDone} = também os concluídos. */
    Slice<Reminder> list(UUID userId, boolean includeDone, PageQuery page);

    /** Abertos com data até {@code day} (para o gerador). */
    List<Reminder> openUntil(UUID userId, LocalDate day);
}
