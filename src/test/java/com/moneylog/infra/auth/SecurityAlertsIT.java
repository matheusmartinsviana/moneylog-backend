package com.moneylog.infra.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.moneylog.core.auth.app.gateways.UserGateway;
import com.moneylog.core.auth.domain.User;
import com.moneylog.core.auth.domain.UserRole;
import com.moneylog.core.notification.app.gateways.PushSender;
import com.moneylog.core.notification.app.gateways.PushSubscriptionGateway;
import com.moneylog.core.notification.app.gateways.PushSubscriptionGateway.PushSubscription;
import com.moneylog.util.IntegrationTest;
import com.moneylog.util.ItAuth;

@IntegrationTest
@Import(SecurityAlertsIT.RecordingPush.class)
@DisplayName("Alertas de segurança por push")
class SecurityAlertsIT {

    record Sent(PushSubscription subscription, PushSender.PushMessage message) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class RecordingPush {

        static final List<Sent> SENT = new CopyOnWriteArrayList<>();

        @Bean
        @Primary
        PushSender recordingPushSender() {
            return new PushSender() {
                @Override
                public Result send(PushSubscription subscription, PushMessage message) {
                    SENT.add(new Sent(subscription, message));
                    return Result.SENT;
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
    private MockMvc mockMvc;
    @Autowired
    private ItAuth auth;
    @Autowired
    private UserGateway users;
    @Autowired
    private PushSubscriptionGateway subscriptions;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;
    private TransactionTemplate tx;

    private UUID otherId;

    @BeforeEach
    void iniciar() {
        tx = new TransactionTemplate(transactionManager);
    }

    @AfterEach
    void limpar() {
        tx.executeWithoutResult(status -> {
            jdbc.update("delete from push_subscriptions");
            if (otherId != null) {
                jdbc.update("delete from users where id = ?", otherId);
            }
        });
        RecordingPush.SENT.clear();
    }

    @Test
    @DisplayName("LOGIN_BLOCKED chega só às inscrições do usuário da conta visada, com IP mascarado e sem dado sensível; e-mail desconhecido não avisa ninguém")
    void deveAlertarSoODonoNoBloqueio() throws Exception {
        UUID ownerId = auth.ownerId();
        otherId = UUID.randomUUID();
        users.insert(User.member(otherId, "outro-" + otherId + "@example.com"));
        tx.executeWithoutResult(status -> {
            jdbc.update("update users set created_at = now() + interval '1 day' where id = ?", otherId);
            subscriptions.upsert(new PushSubscription(UUID.randomUUID(), ownerId, "https://push.example/dono", "p", "a", "ua", Instant.now()));
            subscriptions.upsert(new PushSubscription(UUID.randomUUID(), otherId, "https://push.example/outro", "p", "a", "ua", Instant.now()));
        });
        assertThat(users.findByRole(UserRole.OWNER).orElseThrow().id()).isEqualTo(ownerId);
        String targetEmail = users.findById(otherId).orElseThrow().email();

        for (int i = 0; i < 6; i++) {
            int expected = i < 5 ? 401 : 429;
            mockMvc.perform(post("/v1/auth/login").contentType(MediaType.APPLICATION_JSON).with(request -> {
                request.setRemoteAddr("203.0.113.76");
                return request;
            }).content("{\"email\":\"desconhecido-" + otherId + "@example.com\",\"password\":\"errada\"}")).andExpect(status().is(expected));
        }
        Thread.sleep(500);
        assertThat(RecordingPush.SENT).isEmpty();

        for (int i = 0; i < 6; i++) {
            int expected = i < 5 ? 401 : 429;
            mockMvc.perform(post("/v1/auth/login").contentType(MediaType.APPLICATION_JSON).with(request -> {
                request.setRemoteAddr("203.0.113.77");
                return request;
            }).content("{\"email\":\"" + targetEmail + "\",\"password\":\"errada\"}")).andExpect(status().is(expected));
        }

        long deadline = System.currentTimeMillis() + 5_000;
        while (RecordingPush.SENT.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(25);
        }
        Thread.sleep(200);

        assertThat(RecordingPush.SENT).hasSize(1);
        Sent sent = RecordingPush.SENT.get(0);
        assertThat(sent.subscription().userId()).isEqualTo(otherId);
        assertThat(sent.subscription().endpoint()).isEqualTo("https://push.example/outro");
        assertThat(sent.message().kind()).isEqualTo("SECURITY_ALERT");
        assertThat(sent.message().titleKey()).isEqualTo("security.login_blocked");
        assertThat(sent.message().params()).containsOnly(java.util.Map.entry("ip", "203.0.x.x"), java.util.Map.entry("scope", "ip"));
        assertThat(sent.message().params().toString()).doesNotContain(targetEmail, "errada");
    }
}
