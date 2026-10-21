package com.moneylog.core.invitation.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.moneylog.core.invitation.app.gateways.InvitationGateway;
import com.moneylog.core.invitation.app.usecase.ListInvitationsUseCase.ListInvitationsQuery;
import com.moneylog.core.invitation.app.usecase.ListInvitationsUseCaseImpl;
import com.moneylog.core.invitation.app.usecase.PreviewInvitationUseCase.PreviewInvitationCommand;
import com.moneylog.core.invitation.app.usecase.PreviewInvitationUseCaseImpl;
import com.moneylog.core.invitation.app.usecase.PurgeInvitationsUseCase.PurgeInvitationsCommand;
import com.moneylog.core.invitation.app.usecase.PurgeInvitationsUseCaseImpl;
import com.moneylog.core.invitation.app.usecase.RevokeInvitationUseCase.RevokeInvitationCommand;
import com.moneylog.core.invitation.app.usecase.RevokeInvitationUseCaseImpl;
import com.moneylog.core.invitation.domain.Invitation;
import com.moneylog.core.invitation.domain.InvitationStatus;
import com.moneylog.core.invitation.exception.InvitationExceptions.InvalidInvitationException;
import com.moneylog.core.invitation.exception.InvitationExceptions.InvitationNotFoundException;
import com.moneylog.core.invitation.exception.InvitationExceptions.InvitationNotRevocableException;
import com.moneylog.core.sk.id.SecureToken;
import com.moneylog.core.sk.time.ClockTimeProvider;
import com.moneylog.core.sk.time.TimeProvider;

@ExtendWith(MockitoExtension.class)
@DisplayName("Consultas, revogação e retenção de convites")
class InvitationQueriesAndRetentionTest {

    private static final Instant NOW = Instant.parse("2026-10-19T12:00:00Z");
    private static final UUID OWNER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Mock
    private com.moneylog.core.invitation.app.gateways.InviterNameGateway inviterNames;
    @Mock
    private InvitationGateway invitations;

    private final TimeProvider time = new ClockTimeProvider(Clock.fixed(NOW, ZoneOffset.UTC));
    private Invitation pending;

    @BeforeEach
    void setUp() {
        pending = Invitation.issue(UUID.randomUUID(), "ana@example.com", SecureToken.hash("t"), OWNER_ID, NOW.minusSeconds(60),
                NOW.plus(Duration.ofDays(7)));
    }

    @Test
    @DisplayName("revoga PENDING e grava; repetir não grava de novo")
    void revokes() {
        when(invitations.findByIdForUpdate(pending.id())).thenReturn(Optional.of(pending));
        var view = new RevokeInvitationUseCaseImpl(invitations, time).handle(new RevokeInvitationCommand(pending.id()));
        assertThat(view.status()).isEqualTo(InvitationStatus.REVOKED);
        ArgumentCaptor<Invitation> saved = ArgumentCaptor.forClass(Invitation.class);
        verify(invitations).update(saved.capture());
        assertThat(saved.getValue().status()).isEqualTo(InvitationStatus.REVOKED);

        Invitation revoked = saved.getValue();
        when(invitations.findByIdForUpdate(revoked.id())).thenReturn(Optional.of(revoked));
        var again = new RevokeInvitationUseCaseImpl(invitations, time).handle(new RevokeInvitationCommand(revoked.id()));
        assertThat(again.status()).isEqualTo(InvitationStatus.REVOKED);
        verify(invitations, org.mockito.Mockito.times(1)).update(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("revogar ACCEPTED: 409; id desconhecido: 404")
    void revokeFailures() {
        Invitation accepted = pending.accept(UUID.randomUUID(), NOW);
        when(invitations.findByIdForUpdate(accepted.id())).thenReturn(Optional.of(accepted));
        RevokeInvitationUseCaseImpl useCase = new RevokeInvitationUseCaseImpl(invitations, time);
        assertThatThrownBy(() -> useCase.handle(new RevokeInvitationCommand(accepted.id()))).isInstanceOf(InvitationNotRevocableException.class);
        UUID unknown = UUID.randomUUID();
        when(invitations.findByIdForUpdate(unknown)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> useCase.handle(new RevokeInvitationCommand(unknown))).isInstanceOf(InvitationNotFoundException.class);
        verify(invitations, never()).update(accepted);
    }

    @Test
    @DisplayName("lista com PENDING vencido como EXPIRED e filtra pelo status efetivo")
    void listsWithEffectiveStatus() {
        Invitation overdue = Invitation.issue(UUID.randomUUID(), "b@example.com", SecureToken.hash("o"), OWNER_ID,
                NOW.minus(Duration.ofDays(8)), NOW.minusSeconds(1));
        when(invitations.findAllNewestFirst()).thenReturn(List.of(pending, overdue));
        ListInvitationsUseCaseImpl useCase = new ListInvitationsUseCaseImpl(invitations, time);
        assertThat(useCase.handle(new ListInvitationsQuery(null))).extracting(InvitationView::status)
                .containsExactly(InvitationStatus.PENDING, InvitationStatus.EXPIRED);
        assertThat(useCase.handle(new ListInvitationsQuery(InvitationStatus.EXPIRED))).extracting(InvitationView::email)
                .containsExactly("b@example.com");
        assertThat(useCase.handle(new ListInvitationsQuery(InvitationStatus.PENDING))).hasSize(1);
        assertThat(useCase.handle(new ListInvitationsQuery(InvitationStatus.ACCEPTED))).isEmpty();
    }

    @Test
    @DisplayName("preview devolve e-mail e validade, uma única consulta por hash; inválido é 404")
    void previews() {
        when(invitations.findByTokenHash(SecureToken.hash("t"))).thenReturn(Optional.of(pending));
        PreviewInvitationUseCaseImpl useCase = new PreviewInvitationUseCaseImpl(invitations, inviterNames, time, new InvitationPolicy(true, 7, 10));
        var preview = useCase.handle(new PreviewInvitationCommand("t"));
        assertThat(preview.email()).isEqualTo("ana@example.com");
        assertThat(preview.expiresAt()).isEqualTo(pending.expiresAt());
        assertThat(preview.invitedByName()).isNull();
        when(invitations.findByTokenHash(SecureToken.hash("x"))).thenReturn(Optional.empty());
        assertThatThrownBy(() -> useCase.handle(new PreviewInvitationCommand("x"))).isInstanceOf(InvalidInvitationException.class);
        when(invitations.findByTokenHash(SecureToken.hash("r"))).thenReturn(Optional.of(pending.revoke()));
        assertThatThrownBy(() -> useCase.handle(new PreviewInvitationCommand("r"))).isInstanceOf(InvalidInvitationException.class);
    }

    @Test
    @DisplayName("preview traz o apelido de quem convidou, sem espaços nas pontas, e trata branco como nulo")
    void previewsInviterName() {
        when(invitations.findByTokenHash(SecureToken.hash("t"))).thenReturn(Optional.of(pending));
        PreviewInvitationUseCaseImpl useCase = new PreviewInvitationUseCaseImpl(invitations, inviterNames, time, new InvitationPolicy(true, 7, 10));
        when(inviterNames.findDisplayName(OWNER_ID)).thenReturn(Optional.of("  Matheus "));
        assertThat(useCase.handle(new PreviewInvitationCommand("t")).invitedByName()).isEqualTo("Matheus");
        when(inviterNames.findDisplayName(OWNER_ID)).thenReturn(Optional.of("   "));
        assertThat(useCase.handle(new PreviewInvitationCommand("t")).invitedByName()).isNull();
    }

    @Test
    @DisplayName("preview de token inválido não consulta o apelido")
    void invalidTokenDoesNotLookUpName() {
        when(invitations.findByTokenHash(SecureToken.hash("x"))).thenReturn(Optional.empty());
        PreviewInvitationUseCaseImpl useCase = new PreviewInvitationUseCaseImpl(invitations, inviterNames, time, new InvitationPolicy(true, 7, 10));
        assertThatThrownBy(() -> useCase.handle(new PreviewInvitationCommand("x"))).isInstanceOf(InvalidInvitationException.class);
        org.mockito.Mockito.verifyNoInteractions(inviterNames);
    }

    @Test
    @DisplayName("preview com a flag desligada: 404 INVALID_INVITATION sem consulta")
    void previewDisabled() {
        PreviewInvitationUseCaseImpl useCase = new PreviewInvitationUseCaseImpl(invitations, inviterNames, time, new InvitationPolicy(false, 7, 10));
        assertThatThrownBy(() -> useCase.handle(new PreviewInvitationCommand("t"))).isInstanceOf(InvalidInvitationException.class);
        org.mockito.Mockito.verifyNoInteractions(invitations);
    }

    @Test
    @DisplayName("retenção persiste os vencidos e apaga os finais anteriores ao corte")
    void purges() {
        when(invitations.expireOverdue(NOW)).thenReturn(2);
        when(invitations.deleteFinalBefore(NOW.minus(Duration.ofDays(90)))).thenReturn(5);
        var result = new PurgeInvitationsUseCaseImpl(invitations, time).handle(new PurgeInvitationsCommand(Duration.ofDays(90)));
        assertThat(result.expired()).isEqualTo(2);
        assertThat(result.deleted()).isEqualTo(5);
    }
}
