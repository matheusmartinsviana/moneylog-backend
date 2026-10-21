package com.moneylog.infra.quickentry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.moneylog.core.quickentry.app.gateways.QuickEntryAuditGateway;
import com.moneylog.core.quickentry.app.gateways.QuickEntryTokenGateway;
import com.moneylog.core.quickentry.domain.QuickEntryAudit;
import com.moneylog.core.quickentry.domain.QuickEntryToken;
import com.moneylog.core.quickentry.domain.QuickEntryTokenStatus;
import com.moneylog.core.quickentry.domain.TokenRevocationReason;
import com.moneylog.util.IntegrationTest;

@IntegrationTest
@DisplayName("Gateways do lançamento rápido: persistência e isolamento por usuário")
class QuickEntryGatewaysIT {

    private static final Instant NOW = Instant.parse("2026-10-21T12:00:00Z");
    private static final Instant EXPIRES = NOW.plusSeconds(30L * 24 * 3600);

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private QuickEntryTokenGateway tokens;
    @Autowired
    private QuickEntryAuditGateway audits;

    private final List<UUID> users = new ArrayList<>();

    @AfterEach
    void limpar() {
        for (UUID user : users) {
            write("delete from quick_entry_audit where user_id = ?", user);
            write("delete from quick_entry_tokens where user_id = ?", user);
            write("delete from transactions where user_id = ?", user);
            write("delete from accounts where user_id = ?", user);
            write("delete from users where id = ?", user);
        }
    }

    private void write(String sql, Object... args) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> jdbc.update(sql, args));
    }

    private UUID user() {
        UUID id = UUID.randomUUID();
        write("insert into users (id,email,created_at,updated_at) values (?,?,?,?)", id, "qe-" + id + "@example.com",
                Timestamp.from(NOW), Timestamp.from(NOW));
        users.add(id);
        return id;
    }

    private UUID account(UUID user) {
        UUID id = UUID.randomUUID();
        write("""
                INSERT INTO accounts (id, user_id, name, type, opening_balance_cents, currency, opening_date, status, created_at, updated_at)
                VALUES (?, ?, 'Carteira', 'WALLET', 0, 'BRL', ?, 'ACTIVE', ?, ?)""", id, user, Date.valueOf(LocalDate.of(2026, 10, 1)),
                Timestamp.from(NOW), Timestamp.from(NOW));
        return id;
    }

    private UUID quickTransaction(UUID user, UUID account) {
        UUID id = UUID.randomUUID();
        write("""
                INSERT INTO transactions (id, user_id, type, status, amount_cents, currency, transaction_date, payment_method, account_id,
                    source, created_at, updated_at)
                VALUES (?, ?, 'EXPENSE', 'ACTIVE', 1250, 'BRL', ?, 'PIX', ?, 'QUICK_SHORTCUT', ?, ?)""", id, user,
                Date.valueOf(LocalDate.of(2026, 10, 21)), account, Timestamp.from(NOW), Timestamp.from(NOW));
        return id;
    }

    private QuickEntryToken issue(UUID user, UUID account, char hashChar, Instant createdAt) {
        QuickEntryToken token = QuickEntryToken.issue(UUID.randomUUID(), user, account, "Toque duplo", "abcd1234",
                String.valueOf(hashChar).repeat(64), createdAt, createdAt.plusSeconds(30L * 24 * 3600));
        tokens.insert(token);
        return token;
    }

    @Test
    @DisplayName("grava e lê o token inteiro; o banco guarda só o hash e o prefixo")
    void deveGravarELer() {
        UUID user = user();
        UUID account = account(user);
        QuickEntryToken token = issue(user, account, 'a', NOW);

        QuickEntryToken read = tokens.findById(user, token.id()).orElseThrow();

        assertThat(read.accountId()).isEqualTo(account);
        assertThat(read.tokenHash()).isEqualTo("a".repeat(64));
        assertThat(read.prefix()).isEqualTo("abcd1234");
        assertThat(read.expiresAt()).isEqualTo(EXPIRES);
        assertThat(read.statusAt(NOW)).isEqualTo(QuickEntryTokenStatus.ACTIVE);
        assertThat(read.version()).isZero();
        assertThat(jdbc.queryForObject("select scope from quick_entry_tokens where id = ?", String.class, token.id())).isEqualTo("QUICK_ENTRY");
    }

    @Test
    @DisplayName("busca por hash com outro user_id não encontra; a de autenticação, sem usuário, encontra só pelo hash exato")
    void deveFiltrarPorUsuarioNaBuscaPorHash() {
        UUID dono = user();
        UUID outro = user();
        QuickEntryToken token = issue(dono, account(dono), 'b', NOW);

        assertThat(tokens.findByTokenHash(dono, token.tokenHash())).isPresent();
        assertThat(tokens.findByTokenHash(outro, token.tokenHash())).isEmpty();
        assertThat(tokens.findById(outro, token.id())).isEmpty();
        assertThat(tokens.findByTokenHashForAuthentication(token.tokenHash()).orElseThrow().userId()).isEqualTo(dono);
        assertThat(tokens.findByTokenHashForAuthentication("c".repeat(64))).isEmpty();
    }

    @Test
    @DisplayName("segundo token com o mesmo hash falha na unicidade")
    void deveRejeitarHashRepetido() {
        UUID user = user();
        UUID account = account(user);
        issue(user, account, 'd', NOW);
        assertThatThrownBy(() -> issue(user, account, 'd', NOW)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("token com conta de outro usuário não é gravado")
    void deveRejeitarContaDeOutroUsuario() {
        UUID dono = user();
        UUID outro = user();
        UUID contaDoOutro = account(outro);
        assertThatThrownBy(() -> issue(dono, contaDoOutro, 'e', NOW)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(tokens.listNewestFirst(dono)).isEmpty();
    }

    @Test
    @DisplayName("lista só os tokens do usuário, do mais novo para o mais antigo")
    void deveListarSoOsDoUsuario() {
        UUID dono = user();
        UUID outro = user();
        UUID account = account(dono);
        QuickEntryToken velho = issue(dono, account, 'f', NOW);
        QuickEntryToken novo = issue(dono, account, '1', NOW.plusSeconds(60));
        issue(outro, account(outro), '2', NOW);

        assertThat(tokens.listNewestFirst(dono)).extracting(QuickEntryToken::id).containsExactly(novo.id(), velho.id());
    }

    @Test
    @DisplayName("revogar e renomear persistem pelo update, com versão otimista; outro usuário não atualiza")
    void deveAtualizar() {
        UUID dono = user();
        QuickEntryToken token = issue(dono, account(dono), '3', NOW);

        tokens.update(token.rename("Novo nome").revoke(TokenRevocationReason.USER, NOW.plusSeconds(5)));

        QuickEntryToken read = tokens.findById(dono, token.id()).orElseThrow();
        assertThat(read.label()).isEqualTo("Novo nome");
        assertThat(read.statusAt(NOW.plusSeconds(6))).isEqualTo(QuickEntryTokenStatus.REVOKED);
        assertThat(read.revokedReason()).isEqualTo(TokenRevocationReason.USER);
        assertThat(read.version()).isEqualTo(1);

        QuickEntryToken foreign = new QuickEntryToken(token.id(), user(), token.accountId(), "x", token.prefix(), token.tokenHash(),
                token.scope(), token.expiresAt(), null, null, null, null, null, token.createdAt(), 0L);
        assertThatThrownBy(() -> tokens.update(foreign)).isInstanceOf(RuntimeException.class);
        assertThat(tokens.findById(dono, token.id()).orElseThrow().label()).isEqualTo("Novo nome");
    }

    @Test
    @DisplayName("conta só os ativos: revogado e vencido saem da conta; revogar libera a vaga")
    void deveContarAtivos() {
        UUID dono = user();
        UUID account = account(dono);
        QuickEntryToken a = issue(dono, account, '4', NOW);
        issue(dono, account, '5', NOW);
        issue(dono, account, '6', NOW.minusSeconds(31L * 24 * 3600));

        assertThat(tokens.countActive(dono, NOW)).isEqualTo(2);
        tokens.update(a.revoke(TokenRevocationReason.USER, NOW));
        assertThat(tokens.countActive(dono, NOW)).isEqualTo(1);
    }

    @Test
    @DisplayName("revogar todos os ativos de um usuário usa o motivo dado, não toca em revogado nem em outro usuário")
    void deveRevogarTodosOsAtivos() {
        UUID dono = user();
        UUID outro = user();
        UUID account = account(dono);
        QuickEntryToken jaRevogado = issue(dono, account, '7', NOW);
        tokens.update(jaRevogado.revoke(TokenRevocationReason.USER, NOW.minusSeconds(100)));
        QuickEntryToken ativo = issue(dono, account, '8', NOW);
        QuickEntryToken doOutro = issue(outro, account(outro), '9', NOW);

        assertThat(tokens.revokeAllActive(dono, TokenRevocationReason.USER_DISABLED, NOW)).isEqualTo(1);

        assertThat(tokens.findById(dono, ativo.id()).orElseThrow().revokedReason()).isEqualTo(TokenRevocationReason.USER_DISABLED);
        assertThat(tokens.findById(dono, jaRevogado.id()).orElseThrow().revokedReason()).isEqualTo(TokenRevocationReason.USER);
        assertThat(tokens.findById(dono, jaRevogado.id()).orElseThrow().revokedAt()).isEqualTo(NOW.minusSeconds(100));
        assertThat(tokens.findById(outro, doOutro.id()).orElseThrow().revokedAt()).isNull();
    }

    @Test
    @DisplayName("registro de uso grava no máximo uma vez por janela, com IP mascarado e user agent truncado, e não vale para outro usuário nem revogado")
    void deveRegistrarUsoComJanela() {
        UUID dono = user();
        QuickEntryToken token = issue(dono, account(dono), 'a', NOW);
        Instant first = NOW.plusSeconds(10);

        assertThat(tokens.recordUse(dono, token.id(), first, first.minusSeconds(60), "203.0.113.0", "u".repeat(300))).isTrue();
        assertThat(tokens.recordUse(dono, token.id(), first.plusSeconds(30), first.plusSeconds(30).minusSeconds(60), "198.51.100.0", "x")).isFalse();
        assertThat(tokens.recordUse(user(), token.id(), first.plusSeconds(90), first.plusSeconds(30), "198.51.100.0", "x")).isFalse();
        assertThat(tokens.recordUse(dono, token.id(), first.plusSeconds(90), first.plusSeconds(30), "198.51.100.0", "novo")).isTrue();

        QuickEntryToken read = tokens.findById(dono, token.id()).orElseThrow();
        assertThat(read.lastUsedAt()).isEqualTo(first.plusSeconds(90));
        assertThat(read.lastUsedIp()).isEqualTo("198.51.100.0");
        assertThat(read.lastUsedUserAgent()).isEqualTo("novo");

        tokens.update(read.revoke(TokenRevocationReason.USER, NOW.plusSeconds(200)));
        assertThat(tokens.recordUse(dono, token.id(), first.plusSeconds(500), first.plusSeconds(400), "198.51.100.0", "y")).isFalse();
    }

    @Test
    @DisplayName("user agent gravado é truncado em 120 caracteres")
    void deveTruncarUserAgent() {
        UUID dono = user();
        QuickEntryToken token = issue(dono, account(dono), 'b', NOW);
        tokens.recordUse(dono, token.id(), NOW.plusSeconds(1), NOW, "203.0.113.0", "u".repeat(300));
        assertThat(tokens.findById(dono, token.id()).orElseThrow().lastUsedUserAgent()).hasSize(120);
    }

    @Test
    @DisplayName("auditoria liga movimentação ao token; consulta por outro usuário não vê; a FK composta recusa token de outro usuário")
    void deveAuditarPorToken() {
        UUID dono = user();
        UUID outro = user();
        UUID account = account(dono);
        QuickEntryToken token = issue(dono, account, 'c', NOW);
        UUID tx = quickTransaction(dono, account);

        audits.insert(new QuickEntryAudit(tx, token.id(), dono, "203.0.113.0", NOW));

        QuickEntryAudit read = audits.findByTransactionId(dono, tx).orElseThrow();
        assertThat(read.tokenId()).isEqualTo(token.id());
        assertThat(read.ip()).isEqualTo("203.0.113.0");
        assertThat(audits.findByTransactionId(outro, tx)).isEmpty();
        assertThat(audits.listByToken(dono, token.id(), 10)).extracting(QuickEntryAudit::transactionId).containsExactly(tx);
        assertThat(audits.listByToken(outro, token.id(), 10)).isEmpty();

        UUID txDoOutro = quickTransaction(outro, account(outro));
        assertThatThrownBy(() -> audits.insert(new QuickEntryAudit(txDoOutro, token.id(), outro, null, NOW)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
