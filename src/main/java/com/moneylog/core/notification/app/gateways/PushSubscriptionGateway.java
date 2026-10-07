package com.moneylog.core.notification.app.gateways;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface PushSubscriptionGateway {

    record PushSubscription(UUID id, UUID userId, String endpoint, String p256dh, String auth, String userAgent, Instant createdAt) {
    }

    /** Assinatura nova ou, se o {@code endpoint} já existe para o usuário, a mesma (idempotente); as chaves são atualizadas. */
    PushSubscription upsert(PushSubscription subscription);

    int count(UUID userId);

    /** {@code false} se não existe (ou é de outro usuário). */
    boolean delete(UUID userId, UUID id);

    /** Remove a assinatura que o serviço de push informou como morta (404/410). */
    void deleteByEndpoint(String endpoint);

    List<PushSubscription> listByUser(UUID userId);
}
