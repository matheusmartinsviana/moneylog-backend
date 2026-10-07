package com.moneylog.core.notification.app.gateways;

import java.util.Map;
import java.util.UUID;

import com.moneylog.core.notification.app.gateways.PushSubscriptionGateway.PushSubscription;

/**
 * Porta do push (S-173): {@code NoopPushSender} (padrão, nada sai) ou {@code WebPushSender} (VAPID), atrás da flag {@code moneylog.push.enabled}. O
 * payload leva só o necessário; valores financeiros só se {@code push.includeAmounts=true}.
 */
public interface PushSender {

    /** @param params mínimos (já filtrados: sem valores se o envio de valores está desligado) */
    record PushMessage(UUID notificationId, String kind, String titleKey, Map<String, String> params) {
    }

    enum Result {
        SENT,
        /** 404/410: a assinatura morreu; deve ser removida. */
        GONE,
        /** 429/5xx: tentar de novo mais tarde (recuo). */
        RETRY_LATER,
        FAILED
    }

    Result send(PushSubscription subscription, PushMessage message);

    /** {@code false} no {@code NoopPushSender}: a UI/rotas avisam que o push não está ativado neste servidor. */
    boolean enabled();

    /** Chave pública VAPID para o front assinar; {@code null} se desligado. */
    String publicKey();
}
