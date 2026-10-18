package com.moneylog.infra.notification;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

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

import com.moneylog.core.notification.app.gateways.PushSender;
import com.moneylog.core.notification.app.gateways.PushSubscriptionGateway.PushSubscription;
import com.moneylog.util.IntegrationTest;
import com.moneylog.util.ItAuth;

@IntegrationTest
@Import(SubscribePushEnablesPushIT.EnabledPush.class)
@DisplayName("Assinar um aparelho liga o push da conta")
class SubscribePushEnablesPushIT {

    @TestConfiguration(proxyBeanMethods = false)
    static class EnabledPush {

        @Bean
        @Primary
        PushSender enabledPushSender() {
            return new PushSender() {
                @Override
                public Result send(PushSubscription subscription, PushMessage message) {
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
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private ItAuth auth;

    private UUID user;
    private String bearer;

    @BeforeEach
    void novoUsuario() {
        user = UUID.randomUUID();
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                jdbc.update("insert into users (id,email,created_at,updated_at) values (?,?,now(),now())", user, "sub-" + user + "@example.com"));
        bearer = auth.bearer(user);
    }

    private void subscribe(String endpoint) throws Exception {
        String body = "{\"endpoint\":\"" + endpoint + "\",\"keys\":{\"p256dh\":\"p\",\"auth\":\"a\"},\"userAgent\":\"ua\"}";
        mvc.perform(post("/v1/push/subscriptions").header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("usuário sem linha de configurações: assinar liga o push e as configurações refletem")
    void ligaParaUsuarioSemLinha() throws Exception {
        mvc.perform(get("/v1/settings/notifications").header("Authorization", bearer)).andExpect(jsonPath("$.push.enabled").value(false));

        subscribe("https://fcm.googleapis.com/fcm/send/sem-linha");

        mvc.perform(get("/v1/settings/notifications").header("Authorization", bearer))
                .andExpect(jsonPath("$.push.enabled").value(true)).andExpect(jsonPath("$.version").value(1));
    }

    @Test
    @DisplayName("usuário com linha e push desligado: assinar liga e sobe a version; reassinar o mesmo endpoint não mexe na version")
    void ligaParaUsuarioComLinhaEReassinaIdempotente() throws Exception {
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                jdbc.update("insert into notification_settings (user_id, push_enabled, version, updated_at) values (?, false, 3, now())", user));

        subscribe("https://fcm.googleapis.com/fcm/send/com-linha");
        subscribe("https://fcm.googleapis.com/fcm/send/com-linha");

        mvc.perform(get("/v1/settings/notifications").header("Authorization", bearer))
                .andExpect(jsonPath("$.push.enabled").value(true)).andExpect(jsonPath("$.version").value(4));
    }

    @Test
    @DisplayName("remover o aparelho não desliga o push (o desligamento automático ainda não foi decidido)")
    void removerNaoDesliga() throws Exception {
        subscribe("https://fcm.googleapis.com/fcm/send/remover");
        String id = jdbc.queryForObject("select id from push_subscriptions where user_id = ?", String.class, user);

        mvc.perform(delete("/v1/push/subscriptions/" + id).header("Authorization", bearer)).andExpect(status().isNoContent());

        mvc.perform(get("/v1/settings/notifications").header("Authorization", bearer)).andExpect(jsonPath("$.push.enabled").value(true));
    }
}
