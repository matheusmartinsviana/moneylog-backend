package com.moneylog.infra.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.moneylog.core.notification.app.gateways.PushSender;
import com.moneylog.core.notification.app.gateways.PushSubscriptionGateway;
import com.moneylog.core.notification.app.gateways.PushSubscriptionGateway.PushSubscription;
import com.moneylog.core.notification.app.usecase.NotificationUseCases.DispatchPushUseCase;
import com.moneylog.util.IntegrationTest;
import com.moneylog.util.ItAuth;

@IntegrationTest
@Import(PushDispatchPersistenceIT.GonePush.class)
@DisplayName("Envio de push persiste o resultado")
class PushDispatchPersistenceIT {

    @TestConfiguration(proxyBeanMethods = false)
    static class GonePush {

        static final List<String> ENDPOINTS = new CopyOnWriteArrayList<>();
        static volatile PushSender.Result result = PushSender.Result.SENT;

        @Bean
        @Primary
        PushSender dispatchPushSender() {
            return new PushSender() {
                @Override
                public Result send(PushSubscription subscription, PushMessage message) {
                    ENDPOINTS.add(subscription.endpoint());
                    return result;
                }

                @Override
                public boolean enabled() {
                    return true;
                }

                @Override
                public String publicKey() {
                    return "test";
                }
            };
        }
    }

    @Autowired
    private DispatchPushUseCase dispatch;
    @Autowired
    private PushSubscriptionGateway subscriptions;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private ItAuth auth;

    private TransactionTemplate tx;
    private UUID notificationId;

    @BeforeEach
    void preparar() {
        tx = new TransactionTemplate(transactionManager);
        GonePush.ENDPOINTS.clear();
        GonePush.result = PushSender.Result.SENT;
        UUID owner = auth.ownerId();
        notificationId = UUID.randomUUID();
        tx.executeWithoutResult(status -> {
            jdbc.update("delete from push_subscriptions");
            subscriptions.upsert(new PushSubscription(UUID.randomUUID(), owner, "https://push.example/persistencia", "p", "a", "ua", Instant.now()));
            jdbc.update("""
                    insert into notifications (id, user_id, kind, ref_type, ref_id, on_date, title_key, params, push_eligible, created_at)
                    values (?, ?, 'REMINDER', 'REMINDER', ?, current_date, 'notification.reminder.today', '{}', true, now())""",
                    notificationId, owner, UUID.randomUUID());
        });
    }

    @AfterEach
    void limpar() {
        tx.executeWithoutResult(status -> {
            jdbc.update("delete from notifications where id = ?", notificationId);
            jdbc.update("delete from push_subscriptions");
        });
    }

    @Test
    @DisplayName("a notificação enviada fica marcada no banco e o próximo ciclo não a reenvia")
    void naoReenviaDepoisDeEnviar() {
        dispatch.handle();
        int segundoCiclo = dispatch.handle();

        assertThat(GonePush.ENDPOINTS).containsExactly("https://push.example/persistencia");
        assertThat(segundoCiclo).isZero();
        assertThat(jdbc.queryForObject("select push_sent_at is not null from notifications where id = ?", Boolean.class, notificationId)).isTrue();
    }

    @Test
    @DisplayName("inscrição devolvida como morta (GONE) é apagada de verdade")
    void apagaInscricaoMorta() {
        GonePush.result = PushSender.Result.GONE;

        dispatch.handle();

        assertThat(jdbc.queryForObject("select count(*) from push_subscriptions where endpoint = ?", Integer.class,
                "https://push.example/persistencia")).isZero();
    }
}
