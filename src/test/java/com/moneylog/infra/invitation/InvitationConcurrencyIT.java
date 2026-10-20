package com.moneylog.infra.invitation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import com.moneylog.core.sk.id.SecureToken;
import com.moneylog.util.IntegrationTest;
import com.moneylog.util.MutableClockConfig;

@IntegrationTest
@Import(MutableClockConfig.class)
@TestPropertySource(properties = "moneylog.users.max=1000")
@DisplayName("Convites: concorrência")
class InvitationConcurrencyIT extends InvitationITSupport {

    @Test
    @DisplayName("convidar o mesmo e-mail ao mesmo tempo gera um único PENDING e um único envio")
    void sameEmailInvitedTogether() throws Exception {
        String email = newEmail();
        List<Callable<Integer>> tasks = List.of(
                () -> statusOf(ownerPost("/v1/invitations", "{\"email\":\"" + email + "\"}")),
                () -> statusOf(ownerPost("/v1/invitations", "{\"email\":\"" + email + "\"}")),
                () -> statusOf(ownerPost("/v1/invitations", "{\"email\":\"" + email + "\"}")),
                () -> statusOf(ownerPost("/v1/invitations", "{\"email\":\"" + email + "\"}")));

        List<Integer> statuses = runTogether(tasks);

        assertThat(statuses).containsExactlyInAnyOrder(201, 409, 409, 409);
        assertThat(storedStatuses(email)).containsExactly("PENDING");
        assertThat(fake().sent()).hasSize(1);
    }

    @Test
    @DisplayName("reenvio e aceite do token antigo ao mesmo tempo: resultado coerente, nunca usuário com convite reemitido")
    void resendRacesAccept() throws Exception {
        String email = newEmail();
        UUID id = inviteOk(email);
        String oldToken = lastToken();

        List<Integer> statuses = runTogether(List.of(
                () -> statusOf(ownerPost("/v1/invitations/" + id + "/resend", "")),
                () -> statusOf(accept(oldToken, NEW_PASSWORD, newIp()))));

        int users = jdbc.queryForObject("select count(*) from users where email = ?", Integer.class, email);
        if (statuses.get(1) == 200) {
            assertThat(statuses.get(0)).isEqualTo(409);
            assertThat(users).isEqualTo(1);
            assertThat(dbStatus(id)).isEqualTo("ACCEPTED");
        } else {
            assertThat(statuses).containsExactly(200, 404);
            assertThat(users).isZero();
            assertThat(dbStatus(id)).isEqualTo("PENDING");
            assertThat(jdbc.queryForObject("select token_hash from user_invitations where id = ?", String.class, id))
                    .isEqualTo(SecureToken.hash(lastToken()));
        }
    }

    @Test
    @DisplayName("revogação e aceite ao mesmo tempo: ou aceita e a revogação dá 409, ou revoga e o aceite dá 404")
    void revokeRacesAccept() throws Exception {
        String email = newEmail();
        UUID id = inviteOk(email);
        String token = lastToken();

        List<Integer> statuses = runTogether(List.of(
                () -> statusOf(ownerDelete("/v1/invitations/" + id)),
                () -> statusOf(accept(token, NEW_PASSWORD, newIp()))));

        int users = jdbc.queryForObject("select count(*) from users where email = ?", Integer.class, email);
        if (statuses.get(1) == 200) {
            assertThat(statuses.get(0)).isEqualTo(409);
            assertThat(users).isEqualTo(1);
            assertThat(dbStatus(id)).isEqualTo("ACCEPTED");
        } else {
            assertThat(statuses).containsExactly(200, 404);
            assertThat(users).isZero();
            assertThat(dbStatus(id)).isEqualTo("REVOKED");
        }
    }
}
