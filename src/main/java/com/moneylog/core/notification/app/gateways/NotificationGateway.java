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

/** Persistência da central. Todo método de usuário filtra pelo {@code userId}; id de outro usuário não existe. */
public interface NotificationGateway {

    /** Grava se a identidade {@code (user, kind, refId, onDate)} ainda não existe; {@code false} = já existia (a geração é idempotente, S-174). */
    boolean insertIfAbsent(Notification notification);

    /** Só as não arquivadas, da mais recente para a mais antiga. */
    Slice<Notification> list(UUID userId, boolean unreadOnly, NotificationKind kind, PageQuery page);

    long unreadCount(UUID userId);

    Optional<Notification> find(UUID userId, UUID id);

    /** Grava lida, arquivada e envio de push. */
    void save(Notification notification);

    /** Marca todas as não lidas; devolve quantas. */
    int markAllRead(UUID userId, Instant now);

    /** Identidades {@code kind|refId|onDate} das notificações do dia (inclui arquivadas: a idempotência não depende de arquivar). */
    Set<String> identitiesOn(UUID userId, LocalDate day);

    int countOn(UUID userId, LocalDate day);

    /** Elegíveis a push e ainda não enviadas, mais antigas primeiro. */
    List<Notification> pushPending(int limit);

    /** Arquiva as lidas antes do corte (S-179); devolve quantas. Nada é apagado. */
    int archiveReadBefore(Instant cutoff);

    /** Todos os usuários (o job gera por usuário). */
    List<UUID> allUserIds();
}
