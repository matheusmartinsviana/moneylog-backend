package com.moneylog.infra.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import java.util.function.Consumer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.moneylog.core.auth.app.gateways.RefreshTokenGateway;
import com.moneylog.core.auth.app.gateways.UserGateway;
import com.moneylog.core.auth.app.usecase.EnsureOwnerUseCase.EnsureOwnerCommand;
import com.moneylog.core.auth.app.usecase.EnsureOwnerUseCase.OwnerEnsured;
import com.moneylog.core.auth.app.usecase.EnsureOwnerUseCaseImpl;
import com.moneylog.core.auth.domain.User;
import com.moneylog.core.auth.exception.OwnerEmailConflictException;
import com.moneylog.core.sk.id.IdGenerator;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.util.IntegrationTest;

@IntegrationTest
@DisplayName("Bootstrap do dono com papéis, contra o Postgres real (regra N4 da ADR-032)")
class OwnerBootstrapRolesIT {

    private static final String OWNER_EMAIL = "owner@example.com";

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private UserGateway users;
    @Autowired
    private RefreshTokenGateway refreshTokens;
    @Autowired
    private IdGenerator ids;
    @Autowired
    private TimeProvider time;

    private EnsureOwnerUseCaseImpl useCase() {
        return new EnsureOwnerUseCaseImpl(users, ids, refreshTokens, time);
    }

    private void rolledBack(Consumer<TransactionTemplate> body) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status -> {
            body.accept(tx);
            status.setRollbackOnly();
        });
    }

    private long owners() {
        return jdbc.queryForObject("select count(*) from users where role = 'OWNER'", Long.class);
    }

    private void insertMember(UUID id, String email) {
        users.insert(User.member(id, email));
    }

    @Test
    @DisplayName("dono com o e-mail do ambiente: nada muda, continua um só OWNER")
    void nadaMuda() {
        rolledBack(tx -> {
            OwnerEnsured result = useCase().handle(new EnsureOwnerCommand(OWNER_EMAIL));

            assertThat(result.created()).isFalse();
            assertThat(result.promoted()).isFalse();
            assertThat(owners()).isEqualTo(1);
        });
    }

    @Test
    @DisplayName("e-mail do ambiente pertence a um MEMBER enquanto outro é OWNER: falha sem renomear nem promover, e o índice único não é tocado")
    void falhaQuandoOEmailEDeMembro() {
        rolledBack(tx -> {
            UUID memberId = UUID.randomUUID();
            insertMember(memberId, "membro-n4@example.com");

            assertThatThrownBy(() -> useCase().handle(new EnsureOwnerCommand("Membro-N4@Example.com")))
                    .isInstanceOf(OwnerEmailConflictException.class).hasMessageContaining("MONEYLOG_OWNER_EMAIL");

            assertThat(jdbc.queryForObject("select role from users where id = ?", String.class, memberId)).isEqualTo("MEMBER");
            assertThat(jdbc.queryForObject("select email from users where role = 'OWNER'", String.class)).isEqualTo(OWNER_EMAIL);
            assertThat(owners()).isEqualTo(1);
        });
    }

    @Test
    @DisplayName("sem OWNER e com usuário do e-mail do ambiente: promove esse usuário, sem criar linha nova")
    void promoveQuandoNaoHaDono() {
        rolledBack(tx -> {
            jdbc.update("update users set role = 'MEMBER' where role = 'OWNER'");
            int before = jdbc.queryForObject("select count(*) from users", Integer.class);

            OwnerEnsured result = useCase().handle(new EnsureOwnerCommand(OWNER_EMAIL));

            assertThat(result.promoted()).isTrue();
            assertThat(result.created()).isFalse();
            assertThat(owners()).isEqualTo(1);
            assertThat(jdbc.queryForObject("select email from users where role = 'OWNER'", String.class)).isEqualTo(OWNER_EMAIL);
            assertThat(jdbc.queryForObject("select count(*) from users", Integer.class)).isEqualTo(before);
        });
    }

    @Test
    @DisplayName("sem OWNER e sem usuário no e-mail do ambiente: cria o dono com role OWNER e status ACTIVE")
    void criaQuandoNaoHaNinguem() {
        rolledBack(tx -> {
            jdbc.update("update users set role = 'MEMBER' where role = 'OWNER'");

            OwnerEnsured result = useCase().handle(new EnsureOwnerCommand("novo-dono@example.com"));

            assertThat(result.created()).isTrue();
            assertThat(jdbc.queryForObject("select role || '/' || status from users where email = 'novo-dono@example.com'", String.class))
                    .isEqualTo("OWNER/ACTIVE");
            assertThat(owners()).isEqualTo(1);
        });
    }

    @Test
    @DisplayName("e-mail do ambiente livre e diferente do dono: falha sem renomear e continua o único OWNER")
    void falhaQuandoLivreEDivergente() {
        rolledBack(tx -> {
            assertThatThrownBy(() -> useCase().handle(new EnsureOwnerCommand("renomeado@example.com")))
                    .isInstanceOf(OwnerEmailConflictException.class).hasMessageContaining("MONEYLOG_OWNER_EMAIL");

            assertThat(jdbc.queryForObject("select email from users where role = 'OWNER'", String.class)).isEqualTo(OWNER_EMAIL);
            assertThat(owners()).isEqualTo(1);
        });
    }
}
