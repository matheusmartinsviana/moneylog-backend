package com.moneylog.infra.search.gateways;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.moneylog.core.search.domain.SearchGroup;
import com.moneylog.util.IntegrationTest;

/**
 * S-287: estourar o {@code statement_timeout} de um grupo devolve o grupo vazio e marcado em {@code truncated}, e a transação (o instantâneo
 * {@code REPEATABLE_READ} dos outros grupos) <b>continua viva</b>: no Postgres uma consulta cancelada aborta a transação, e só o savepoint a salva.
 */
@IntegrationTest
@DisplayName("Busca global: limite de tempo por grupo com savepoint (S-287)")
class SearchTimeoutIT {

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager tm;

    private TransactionTemplate snapshot() {
        DefaultTransactionDefinition definition = new DefaultTransactionDefinition();
        definition.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        definition.setReadOnly(true);
        return new TransactionTemplate(tm, definition);
    }

    @Test
    @DisplayName("um grupo lento estoura em 200 ms → vazio e truncated; o grupo seguinte, na mesma transação REPEATABLE_READ, responde normalmente")
    void deveTruncarSoOGrupoLento() {
        SearchGatewayImpl gateway = new SearchGatewayImpl(jdbc, Duration.ofMillis(200));

        snapshot().executeWithoutResult(status -> jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
            Set<SearchGroup> truncated = EnumSet.noneOf(SearchGroup.class);
            List<Integer> slow = gateway.group(connection, SearchGroup.TRANSACTIONS, truncated, c -> {
                try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM pg_sleep(3)"); ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? List.of(rs.getInt(1)) : List.of();
                }
            });
            List<String> fast = gateway.group(connection, SearchGroup.ACCOUNTS, truncated, c -> {
                try (PreparedStatement ps = c.prepareStatement("SELECT current_setting('transaction_isolation')"); ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return List.of(rs.getString(1));
                }
            });
            assertThat(slow).isEmpty();
            assertThat(truncated).containsExactly(SearchGroup.TRANSACTIONS);
            assertThat(fast).as("a transação não foi abortada pelo cancelamento").containsExactly("repeatable read");
            return null;
        }));
    }

    @Test
    @DisplayName("dentro do teto o grupo volta normal e o teto é por grupo (cada consulta recomeça a contagem)")
    void deveRecomecarOTetoACadaGrupo() {
        SearchGatewayImpl gateway = new SearchGatewayImpl(jdbc, Duration.ofMillis(900));

        snapshot().executeWithoutResult(status -> jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
            Set<SearchGroup> truncated = EnumSet.noneOf(SearchGroup.class);
            for (SearchGroup group : new SearchGroup[] {SearchGroup.TRANSACTIONS, SearchGroup.ACCOUNTS, SearchGroup.CATEGORIES}) {
                List<Integer> rows = gateway.group(connection, group, truncated, c -> {
                    try (PreparedStatement ps = c.prepareStatement("SELECT 7 FROM pg_sleep(0.4)"); ResultSet rs = ps.executeQuery()) {
                        return rs.next() ? List.of(rs.getInt(1)) : List.of();
                    }
                });
                assertThat(rows).containsExactly(7);
            }
            assertThat(truncated).isEmpty();
            return null;
        }));
    }

    @Test
    @DisplayName("erro de SQL que não é cancelamento vira SearchFailedException só com o SQLState (sem texto da consulta nem parâmetros)")
    void deveEsconderODetalheDaFalha() {
        SearchGatewayImpl gateway = new SearchGatewayImpl(jdbc, Duration.ofSeconds(3));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> snapshot().executeWithoutResult(status ->
                jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
                    gateway.group(connection, SearchGroup.NOTES, EnumSet.noneOf(SearchGroup.class), c -> {
                        try (PreparedStatement ps = c.prepareStatement("SELECT 'segredo-do-usuario'::int"); ResultSet rs = ps.executeQuery()) {
                            return List.of(rs.getInt(1));
                        }
                    });
                    return null;
                }))).isInstanceOf(SearchGatewayImpl.SearchFailedException.class).hasMessageContaining("SQLState 22P02")
                .hasMessageNotContaining("segredo-do-usuario").hasNoCause();
    }
}
