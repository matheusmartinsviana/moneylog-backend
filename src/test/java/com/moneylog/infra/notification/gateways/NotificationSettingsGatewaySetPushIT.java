package com.moneylog.infra.notification.gateways;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.moneylog.core.notification.app.gateways.NotificationSettingsGateway;
import com.moneylog.core.notification.domain.NotificationSettings;
import com.moneylog.util.IntegrationTest;

@IntegrationTest
@DisplayName("setPushEnabled atômico no gateway de configurações")
class NotificationSettingsGatewaySetPushIT {

    @Autowired
    private NotificationSettingsGateway gateway;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private UUID user;

    @BeforeEach
    void novoUsuario() {
        user = UUID.randomUUID();
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                jdbc.update("insert into users (id,email,created_at,updated_at) values (?,?,now(),now())", user, "push-" + user + "@example.com"));
    }

    private <T> T tx(Supplier<T> work) {
        return new TransactionTemplate(transactionManager).execute(status -> work.get());
    }

    private boolean push(UUID id, boolean enabled) {
        return tx(() -> gateway.setPushEnabled(id, enabled));
    }

    private Optional<NotificationSettings> save(NotificationSettings settings, long version) {
        return tx(() -> gateway.save(settings, version));
    }

    private Map<String, Object> row() {
        return jdbc.queryForMap("select * from notification_settings where user_id = ?", user);
    }

    @Test
    @DisplayName("sem linha, ligar cria a linha com os defaults, push ligado e version 1")
    void ligarSemLinha() {
        assertThat(push(user, true)).isTrue();

        Map<String, Object> row = row();
        assertThat(row.get("push_enabled")).isEqualTo(true);
        assertThat(row.get("version")).isEqualTo(1L);
        assertThat(row.get("summary_frequency")).isEqualTo("DAILY");
        assertThat(row.get("summary_time").toString()).isEqualTo("08:00:00");
        assertThat(row.get("quiet_from").toString()).isEqualTo("22:00:00");
        assertThat(row.get("quiet_to").toString()).isEqualTo("07:00:00");
        assertThat(row.get("disabled_kinds")).isEqualTo("");
        assertThat(gateway.find(user).orElseThrow().pushEnabled()).isTrue();
    }

    @Test
    @DisplayName("desligado na versão 3, ligar muda o flag, sobe a version para 4 e preserva as demais colunas")
    void ligarDesligado() {
        tx(() -> jdbc.update("insert into notification_settings (user_id, summary_enabled, summary_frequency, display_name, push_enabled, version, updated_at) "
                + "values (?, false, 'ONLY_DUE', 'Mat', false, 3, now())", user));

        assertThat(push(user, true)).isTrue();

        Map<String, Object> row = row();
        assertThat(row.get("push_enabled")).isEqualTo(true);
        assertThat(row.get("version")).isEqualTo(4L);
        assertThat(row.get("summary_enabled")).isEqualTo(false);
        assertThat(row.get("summary_frequency")).isEqualTo("ONLY_DUE");
        assertThat(row.get("display_name")).isEqualTo("Mat");
    }

    @Test
    @DisplayName("já ligado, ligar de novo não muda a version e devolve false")
    void ligarJaLigado() {
        push(user, true);

        assertThat(push(user, true)).isFalse();

        assertThat(row().get("version")).isEqualTo(1L);
    }

    @Test
    @DisplayName("ligado, desligar muda o flag e sobe a version")
    void desligar() {
        push(user, true);

        assertThat(push(user, false)).isTrue();

        assertThat(row().get("push_enabled")).isEqualTo(false);
        assertThat(row().get("version")).isEqualTo(2L);
    }

    @Test
    @DisplayName("sem linha, desligar não cria nada e devolve false")
    void desligarSemLinha() {
        assertThat(push(user, false)).isFalse();

        assertThat(jdbc.queryForObject("select count(*) from notification_settings where user_id = ?", Integer.class, user)).isZero();
    }

    @Test
    @DisplayName("salvar com a versão anterior à ligação dá conflito e o flag continua ligado")
    void saveComVersaoAntigaConflita() {
        NotificationSettings antiga = save(NotificationSettings.defaults(user), 0).orElseThrow();
        push(user, true);

        assertThat(save(antiga, antiga.version())).isEmpty();

        assertThat(gateway.find(user).orElseThrow().pushEnabled()).isTrue();
    }
}
