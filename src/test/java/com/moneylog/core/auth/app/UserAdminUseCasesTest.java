package com.moneylog.core.auth.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.moneylog.core.auth.app.gateways.RefreshTokenGateway;
import com.moneylog.core.auth.app.gateways.UserGateway;
import com.moneylog.core.auth.app.usecase.DisableUserUseCase.DisableUserCommand;
import com.moneylog.core.auth.app.usecase.DisableUserUseCaseImpl;
import com.moneylog.core.auth.app.usecase.EnableUserUseCase.EnableUserCommand;
import com.moneylog.core.auth.app.usecase.EnableUserUseCaseImpl;
import com.moneylog.core.auth.app.usecase.ListUsersUseCaseImpl;
import com.moneylog.core.auth.domain.ManagedUser;
import com.moneylog.core.auth.domain.User;
import com.moneylog.core.auth.domain.UserRole;
import com.moneylog.core.auth.domain.UserStatus;
import com.moneylog.core.auth.exception.UserManagementExceptions.CannotDisableOwnerException;
import com.moneylog.core.auth.exception.UserManagementExceptions.UserNotFoundException;
import com.moneylog.core.quickentry.app.gateways.QuickEntryTokenGateway;
import com.moneylog.core.quickentry.app.gateways.QuickEntryUserLock;
import com.moneylog.core.quickentry.domain.TokenRevocationReason;
import com.moneylog.core.sk.time.ClockTimeProvider;

@ExtendWith(MockitoExtension.class)
@DisplayName("Gestão de usuários pelo dono")
class UserAdminUseCasesTest {

    private static final Instant NOW = Instant.parse("2026-10-19T12:00:00Z");
    private static final UUID OWNER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID MEMBER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000b1");

    @Mock
    private UserGateway users;
    @Mock
    private RefreshTokenGateway refreshTokens;
    @Mock
    private QuickEntryTokenGateway quickEntryTokens;
    @Mock
    private QuickEntryUserLock quickEntryLock;

    private final ClockTimeProvider time = new ClockTimeProvider(Clock.fixed(NOW, ZoneOffset.UTC), ZoneOffset.UTC);

    private static ManagedUser managed(UUID id, UserRole role, UserStatus status) {
        return new ManagedUser(id, "u@example.com", role, status, NOW.minusSeconds(60), null);
    }

    @Test
    @DisplayName("domínio: membro desativa e reativa mantendo id, e-mail e papel; dono não desativa")
    void dominio() {
        User member = User.member(MEMBER_ID, "m@example.com");

        User disabled = member.disabled();

        assertThat(disabled.status()).isEqualTo(UserStatus.DISABLED);
        assertThat(disabled.isActive()).isFalse();
        assertThat(disabled.enabled()).isEqualTo(member);
        assertThat(disabled.disabled()).isEqualTo(disabled);
        assertThatThrownBy(() -> User.owner(OWNER_ID, "o@example.com").disabled()).isInstanceOf(CannotDisableOwnerException.class);
    }

    @Test
    @DisplayName("listar devolve o que o gateway entrega")
    void listar() {
        List<ManagedUser> all = List.of(managed(OWNER_ID, UserRole.OWNER, UserStatus.ACTIVE));
        when(users.listManaged()).thenReturn(all);

        assertThat(new ListUsersUseCaseImpl(users).handle()).isSameAs(all);
    }

    @Test
    @DisplayName("desativar grava o status e revoga os refresh tokens do usuário com o mesmo instante")
    void desativar() {
        when(users.findById(MEMBER_ID)).thenReturn(Optional.of(User.member(MEMBER_ID, "m@example.com")));
        ManagedUser result = managed(MEMBER_ID, UserRole.MEMBER, UserStatus.DISABLED);
        when(users.findManagedById(MEMBER_ID)).thenReturn(Optional.of(result));

        ManagedUser out = new DisableUserUseCaseImpl(users, refreshTokens, quickEntryTokens, quickEntryLock, time).handle(new DisableUserCommand(MEMBER_ID));

        assertThat(out).isSameAs(result);
        InOrder order = inOrder(quickEntryLock, users, refreshTokens, quickEntryTokens);
        order.verify(quickEntryLock).lock(MEMBER_ID);
        order.verify(users).updateStatus(MEMBER_ID, UserStatus.DISABLED, NOW);
        order.verify(refreshTokens).revokeAllByUser(MEMBER_ID, NOW);
        order.verify(quickEntryTokens).revokeAllActive(MEMBER_ID, TokenRevocationReason.USER_DISABLED, NOW);
    }

    @Test
    @DisplayName("desativar quem já está desativado repete a atualização idempotente e revoga de novo")
    void desativarIdempotente() {
        when(users.findById(MEMBER_ID)).thenReturn(Optional.of(new User(MEMBER_ID, "m@example.com", UserRole.MEMBER, UserStatus.DISABLED)));
        when(users.findManagedById(MEMBER_ID)).thenReturn(Optional.of(managed(MEMBER_ID, UserRole.MEMBER, UserStatus.DISABLED)));

        new DisableUserUseCaseImpl(users, refreshTokens, quickEntryTokens, quickEntryLock, time).handle(new DisableUserCommand(MEMBER_ID));

        verify(users).updateStatus(MEMBER_ID, UserStatus.DISABLED, NOW);
        verify(refreshTokens).revokeAllByUser(MEMBER_ID, NOW);
    }

    @Test
    @DisplayName("desativar o dono falha com CANNOT_DISABLE_OWNER e não toca em nada")
    void naoDesativaDono() {
        when(users.findById(OWNER_ID)).thenReturn(Optional.of(User.owner(OWNER_ID, "o@example.com")));

        assertThatThrownBy(() -> new DisableUserUseCaseImpl(users, refreshTokens, quickEntryTokens, quickEntryLock, time).handle(new DisableUserCommand(OWNER_ID)))
                .isInstanceOf(CannotDisableOwnerException.class);

        verify(users, never()).updateStatus(any(), any(), any());
        verify(refreshTokens, never()).revokeAllByUser(any(), any());
    }

    @Test
    @DisplayName("desativar e reativar usuário inexistente falham com USER_NOT_FOUND")
    void inexistente() {
        when(users.findById(MEMBER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> new DisableUserUseCaseImpl(users, refreshTokens, quickEntryTokens, quickEntryLock, time).handle(new DisableUserCommand(MEMBER_ID)))
                .isInstanceOf(UserNotFoundException.class);
        assertThatThrownBy(() -> new EnableUserUseCaseImpl(users, time).handle(new EnableUserCommand(MEMBER_ID)))
                .isInstanceOf(UserNotFoundException.class);
    }

    @Test
    @DisplayName("reativar volta a ACTIVE; já ativo repete a atualização idempotente")
    void reativar() {
        when(users.findById(MEMBER_ID)).thenReturn(Optional.of(new User(MEMBER_ID, "m@example.com", UserRole.MEMBER, UserStatus.DISABLED)));
        ManagedUser result = managed(MEMBER_ID, UserRole.MEMBER, UserStatus.ACTIVE);
        when(users.findManagedById(MEMBER_ID)).thenReturn(Optional.of(result));

        assertThat(new EnableUserUseCaseImpl(users, time).handle(new EnableUserCommand(MEMBER_ID))).isSameAs(result);
        verify(users).updateStatus(MEMBER_ID, UserStatus.ACTIVE, NOW);

        when(users.findById(MEMBER_ID)).thenReturn(Optional.of(User.member(MEMBER_ID, "m@example.com")));
        new EnableUserUseCaseImpl(users, time).handle(new EnableUserCommand(MEMBER_ID));
        verify(users, org.mockito.Mockito.times(2)).updateStatus(MEMBER_ID, UserStatus.ACTIVE, NOW);
    }
}
