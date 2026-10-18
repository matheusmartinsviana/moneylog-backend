package com.moneylog.infra.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.moneylog.core.notification.app.gateways.PushSender;
import com.moneylog.core.notification.app.gateways.PushSubscriptionGateway.PushSubscription;
import com.moneylog.util.IntegrationTest;
import com.moneylog.util.ItAuth;

@IntegrationTest
@Import(SubscribePushAtomicityAndIsolationIT.EnabledPush.class)
@DisplayName("Assinar um aparelho: atomicidade e isolamento entre usuários")
class SubscribePushAtomicityAndIsolationIT {

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

    private ResultActions subscribe(String bearerToken, String endpoint) throws Exception {
        String body = "{\"endpoint\":\"" + endpoint + "\",\"keys\":{\"p256dh\":\"p\",\"auth\":\"a\"},\"userAgent\":\"ua\"}";
        return mvc.perform(post("/v1/push/subscriptions").header("Authorization", bearerToken).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private UUID novoUsuario(String prefixo) {
        UUID id = UUID.randomUUID();
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                jdbc.update("insert into users (id,email,created_at,updated_at) values (?,?,now(),now())", id, prefixo + "-" + id + "@example.com"));
        return id;
    }

    @Test
    @DisplayName("falha ao ligar o push desfaz a assinatura gravada na mesma transação")
    void falhaAoLigarDesfazAssinatura() throws Exception {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status -> {
            jdbc.execute("create or replace function fail_push_enable() returns trigger as $$ begin raise exception 'falha forcada'; end; $$ language plpgsql");
            jdbc.execute("create trigger fail_push_enable_trg before insert or update on notification_settings for each row when (new.user_id = '" + user + "') execute function fail_push_enable()");
        });
        try {
            subscribe(bearer, "https://fcm.googleapis.com/fcm/send/atomico").andExpect(status().is5xxServerError());
        } finally {
            tx.executeWithoutResult(status -> {
                jdbc.execute("drop trigger if exists fail_push_enable_trg on notification_settings");
                jdbc.execute("drop function if exists fail_push_enable()");
            });
        }

        Integer assinaturas = jdbc.queryForObject("select count(*) from push_subscriptions where user_id = ?", Integer.class, user);
        Integer configuracoes = jdbc.queryForObject("select count(*) from notification_settings where user_id = ?", Integer.class, user);
        assertThat(assinaturas).isZero();
        assertThat(configuracoes).isZero();
    }

    @Test
    @DisplayName("assinar como usuário A não altera as configurações nem as assinaturas do usuário B")
    void ligarAnaoAlteraB() throws Exception {
        UUID outro = novoUsuario("outro");
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                jdbc.update("insert into notification_settings (user_id, push_enabled, version, updated_at) values (?, false, 5, now())", outro));
        UUID semLinha = novoUsuario("sem-linha");

        subscribe(bearer, "https://fcm.googleapis.com/fcm/send/isolamento").andExpect(status().isCreated());

        Boolean outroLigado = jdbc.queryForObject("select push_enabled from notification_settings where user_id = ?", Boolean.class, outro);
        Integer outroVersion = jdbc.queryForObject("select version from notification_settings where user_id = ?", Integer.class, outro);
        Integer semLinhaConfig = jdbc.queryForObject("select count(*) from notification_settings where user_id = ?", Integer.class, semLinha);
        Integer assinaturasOutro = jdbc.queryForObject("select count(*) from push_subscriptions where user_id in (?, ?)", Integer.class, outro, semLinha);
        Boolean meuLigado = jdbc.queryForObject("select push_enabled from notification_settings where user_id = ?", Boolean.class, user);
        assertThat(outroLigado).isFalse();
        assertThat(outroVersion).isEqualTo(5);
        assertThat(semLinhaConfig).isZero();
        assertThat(assinaturasOutro).isZero();
        assertThat(meuLigado).isTrue();
    }
}
