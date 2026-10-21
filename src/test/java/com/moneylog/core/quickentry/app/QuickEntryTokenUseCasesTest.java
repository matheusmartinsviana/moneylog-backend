package com.moneylog.core.quickentry.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.moneylog.core.account.app.gateways.AccountGateway;
import com.moneylog.core.account.domain.Account;
import com.moneylog.core.account.domain.AccountStatus;
import com.moneylog.core.account.domain.AccountType;
import com.moneylog.core.account.exception.AccountExceptions.AccountNotFoundException;
import com.moneylog.core.quickentry.app.gateways.QuickEntryTokenGateway;
import com.moneylog.core.quickentry.app.gateways.QuickEntryUserLock;
import com.moneylog.core.quickentry.app.usecase.CreateQuickEntryTokenUseCase.CreateQuickEntryTokenCommand;
import com.moneylog.core.quickentry.app.usecase.CreateQuickEntryTokenUseCaseImpl;
import com.moneylog.core.quickentry.app.usecase.RenameQuickEntryTokenUseCase.RenameQuickEntryTokenCommand;
import com.moneylog.core.quickentry.app.usecase.RenameQuickEntryTokenUseCaseImpl;
import com.moneylog.core.quickentry.app.usecase.RevokeQuickEntryTokenUseCase.Revoked;
import com.moneylog.core.quickentry.app.usecase.RevokeQuickEntryTokenUseCase.RevokeQuickEntryTokenCommand;
import com.moneylog.core.quickentry.app.usecase.RevokeQuickEntryTokenUseCaseImpl;
import com.moneylog.core.quickentry.domain.QuickEntryToken;
import com.moneylog.core.quickentry.domain.QuickEntryTokenPolicy;
import com.moneylog.core.quickentry.domain.QuickEntryTokenSecret;
import com.moneylog.core.quickentry.domain.QuickEntryTokenStatus;
import com.moneylog.core.quickentry.domain.TokenRevocationReason;
import com.moneylog.core.quickentry.exception.QuickEntryExceptions.QuickAccountNotAllowedException;
import com.moneylog.core.quickentry.exception.QuickEntryExceptions.QuickTokenLimitReachedException;
import com.moneylog.core.quickentry.exception.QuickEntryExceptions.QuickTokenNotFoundException;
import com.moneylog.core.quickentry.exception.QuickEntryExceptions.QuickUserNotActiveException;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.persistence.Persisted;
import com.moneylog.core.sk.time.ClockTimeProvider;

@ExtendWith(MockitoExtension.class)
@DisplayName("Casos de uso dos tokens de lançamento rápido")
class QuickEntryTokenUseCasesTest {

    private static final Instant NOW = Instant.parse("2026-10-21T12:00:00Z");
    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID ACCOUNT = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    private static final UUID TOKEN = UUID.fromString("00000000-0000-0000-0000-0000000000d1");

    @Mock
    private QuickEntryTokenGateway tokens;
    @Mock
    private AccountGateway accounts;
    @Mock
    private QuickEntryUserLock userLock;

    private final ClockTimeProvider time = new ClockTimeProvider(Clock.fixed(NOW, ZoneOffset.UTC), ZoneOffset.UTC);
    private final QuickEntryTokenPolicy policy = new QuickEntryTokenPolicy(30, 90, 3);

    private CreateQuickEntryTokenUseCaseImpl create() {
        return new CreateQuickEntryTokenUseCaseImpl(tokens, accounts, userLock, policy, () -> TOKEN, time);
    }

    private Account account(AccountType type, AccountStatus status) {
        return Account.restore(ACCOUNT, USER, "Carteira", type, null, Money.zero(CurrencyCode.BRL), LocalDate.parse("2026-10-01"), status, 0L);
    }

    private void accountExists(AccountType type, AccountStatus status) {
        when(accounts.findById(USER, ACCOUNT)).thenReturn(Optional.of(new Persisted<>(account(type, status), NOW, NOW)));
    }

    private void userActive(boolean active) {
        when(userLock.lock(USER)).thenReturn(active);
    }

    private QuickEntryToken stored() {
        return QuickEntryToken.issue(TOKEN, USER, ACCOUNT, "iPhone", "abcd1234", "a".repeat(64), NOW.minusSeconds(60), NOW.plusSeconds(86400));
    }

    @Test
    @DisplayName("cria com trava do usuário antes da contagem, grava só prefixo e hash e devolve o segredo uma vez")
    void deveCriar() {
        userActive(true);
        accountExists(AccountType.WALLET, AccountStatus.ACTIVE);
        when(tokens.countActive(USER, NOW)).thenReturn(2L);

        IssuedQuickEntryToken issued = create().handle(new CreateQuickEntryTokenCommand(USER, "iPhone", ACCOUNT, null));

        ArgumentCaptor<QuickEntryToken> saved = ArgumentCaptor.forClass(QuickEntryToken.class);
        InOrder order = inOrder(userLock, tokens);
        order.verify(userLock).lock(USER);
        order.verify(tokens).countActive(USER, NOW);
        order.verify(tokens).insert(saved.capture());
        assertThat(issued.secret()).startsWith("mlq_" + saved.getValue().prefix() + "_");
        assertThat(saved.getValue().tokenHash()).isEqualTo(QuickEntryTokenSecret.hashOf(issued.secret()));
        assertThat(saved.getValue().expiresAt()).isEqualTo(NOW.plusSeconds(30L * 86400));
        assertThat(issued.view().accountName()).isEqualTo("Carteira");
        assertThat(issued.view().status()).isEqualTo(QuickEntryTokenStatus.ACTIVE);
        assertThat(issued.toString()).doesNotContain(issued.secret());
    }

    @Test
    @DisplayName("conta de outro usuário ou inexistente falha com ACCOUNT_NOT_FOUND e nada é gravado")
    void naoDeveCriarComContaAlheia() {
        userActive(true);
        when(accounts.findById(USER, ACCOUNT)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> create().handle(new CreateQuickEntryTokenCommand(USER, "iPhone", ACCOUNT, null)))
                .isInstanceOf(AccountNotFoundException.class);

        verify(tokens, never()).insert(any());
    }

    @Test
    @DisplayName("conta de investimento ou arquivada falha com QUICK_ACCOUNT_NOT_ALLOWED e nada é gravado")
    void naoDeveCriarComContaNaoPermitida() {
        userActive(true);
        accountExists(AccountType.INVESTMENT, AccountStatus.ACTIVE);
        assertThatThrownBy(() -> create().handle(new CreateQuickEntryTokenCommand(USER, "iPhone", ACCOUNT, null)))
                .isInstanceOf(QuickAccountNotAllowedException.class);
        accountExists(AccountType.CHECKING, AccountStatus.ARCHIVED);
        assertThatThrownBy(() -> create().handle(new CreateQuickEntryTokenCommand(USER, "iPhone", ACCOUNT, null)))
                .isInstanceOf(QuickAccountNotAllowedException.class);

        verify(tokens, never()).insert(any());
    }

    @Test
    @DisplayName("com 3 ativos o quarto falha com QUICK_TOKEN_LIMIT_REACHED e nada é gravado")
    void naoDeveUltrapassarLimite() {
        userActive(true);
        accountExists(AccountType.CHECKING, AccountStatus.ACTIVE);
        when(tokens.countActive(USER, NOW)).thenReturn(3L);

        assertThatThrownBy(() -> create().handle(new CreateQuickEntryTokenCommand(USER, "iPhone", ACCOUNT, null)))
                .isInstanceOf(QuickTokenLimitReachedException.class);

        verify(tokens, never()).insert(any());
    }

    @Test
    @DisplayName("revogar grava USER uma vez; repetir devolve o mesmo revokedAt sem gravar de novo")
    void deveRevogarComIdempotencia() {
        accountExists(AccountType.WALLET, AccountStatus.ACTIVE);
        when(tokens.findById(USER, TOKEN)).thenReturn(Optional.of(stored()));
        var useCase = new RevokeQuickEntryTokenUseCaseImpl(tokens, accounts, userLock, time);

        Revoked first = useCase.handle(new RevokeQuickEntryTokenCommand(USER, TOKEN));

        ArgumentCaptor<QuickEntryToken> saved = ArgumentCaptor.forClass(QuickEntryToken.class);
        verify(tokens).update(saved.capture());
        assertThat(first.changed()).isTrue();
        assertThat(first.view().status()).isEqualTo(QuickEntryTokenStatus.REVOKED);
        assertThat(saved.getValue().revokedReason()).isEqualTo(TokenRevocationReason.USER);

        when(tokens.findById(USER, TOKEN)).thenReturn(Optional.of(saved.getValue()));
        Revoked second = useCase.handle(new RevokeQuickEntryTokenCommand(USER, TOKEN));

        assertThat(second.changed()).isFalse();
        assertThat(second.view().token().revokedAt()).isEqualTo(NOW);
        verify(tokens).update(any());
    }

    @Test
    @DisplayName("revogar e renomear token de outro usuário falham com QUICK_TOKEN_NOT_FOUND")
    void naoDeveTocarTokenAlheio() {
        when(tokens.findById(USER, TOKEN)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> new RevokeQuickEntryTokenUseCaseImpl(tokens, accounts, userLock, time).handle(new RevokeQuickEntryTokenCommand(USER, TOKEN)))
                .isInstanceOf(QuickTokenNotFoundException.class);
        assertThatThrownBy(() -> new RenameQuickEntryTokenUseCaseImpl(tokens, accounts, userLock, time)
                .handle(new RenameQuickEntryTokenCommand(USER, TOKEN, "Novo"))).isInstanceOf(QuickTokenNotFoundException.class);

        verify(tokens, never()).update(any());
    }

    @Test
    @DisplayName("usuário desativado não cria token: falha com UNAUTHENTICATED e nada é gravado")
    void naoDeveCriarParaUsuarioDesativado() {
        userActive(false);

        assertThatThrownBy(() -> create().handle(new CreateQuickEntryTokenCommand(USER, "iPhone", ACCOUNT, null)))
                .isInstanceOf(QuickUserNotActiveException.class);

        verify(tokens, never()).insert(any());
    }

    @Test
    @DisplayName("revogar e renomear tomam a trava do usuário antes de ler o token")
    void deveTravarAntesDeLer() {
        accountExists(AccountType.WALLET, AccountStatus.ACTIVE);
        when(tokens.findById(USER, TOKEN)).thenReturn(Optional.of(stored()));

        new RevokeQuickEntryTokenUseCaseImpl(tokens, accounts, userLock, time).handle(new RevokeQuickEntryTokenCommand(USER, TOKEN));
        new RenameQuickEntryTokenUseCaseImpl(tokens, accounts, userLock, time).handle(new RenameQuickEntryTokenCommand(USER, TOKEN, "Novo"));

        InOrder order = inOrder(userLock, tokens);
        order.verify(userLock).lock(USER);
        order.verify(tokens).findById(USER, TOKEN);
        order.verify(userLock).lock(USER);
        order.verify(tokens).findById(USER, TOKEN);
    }

    @Test
    @DisplayName("renomear muda só o rótulo")
    void deveRenomear() {
        accountExists(AccountType.WALLET, AccountStatus.ACTIVE);
        when(tokens.findById(USER, TOKEN)).thenReturn(Optional.of(stored()));

        QuickEntryTokenView view = new RenameQuickEntryTokenUseCaseImpl(tokens, accounts, userLock, time)
                .handle(new RenameQuickEntryTokenCommand(USER, TOKEN, " Atalho novo "));

        assertThat(view.token().label()).isEqualTo("Atalho novo");
        assertThat(view.token().tokenHash()).isEqualTo("a".repeat(64));
        verify(tokens).update(view.token());
    }
}
