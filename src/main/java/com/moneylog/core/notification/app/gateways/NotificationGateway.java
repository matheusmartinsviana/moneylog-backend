package com.moneylog.core.notification.app.gateways;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.moneylog.core.notification.domain.Notification;
import com.moneylog.core.notification.domain.NotificationKind;
import com.moneylog.core.sk.page.PageQuery;
import com.moneylog.core.sk.page.Slice;

public interface NotificationGateway {

    boolean insertIfAbsent(Notification notification);

    Slice<Notification> list(UUID userId, boolean unreadOnly, NotificationKind kind, PageQuery page);

    long unreadCount(UUID userId);

    Optional<Notification> find(UUID userId, UUID id);

    void save(Notification notification);

    int markAllRead(UUID userId, Instant now);

    Set<String> identitiesOn(UUID userId, LocalDate day);

    int countOn(UUID userId, LocalDate day);

    List<Notification> pushPending(int limit);

    int archiveReadBefore(Instant cutoff);

    List<UUID> allUserIds();
}
