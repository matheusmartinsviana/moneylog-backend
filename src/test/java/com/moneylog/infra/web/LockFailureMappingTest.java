package com.moneylog.infra.web;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.SQLException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.orm.jpa.JpaSystemException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.moneylog.infra.exception.GlobalExceptionHandler;

import jakarta.persistence.PessimisticLockException;

/**
 * B2/S-215: falha de trava ou deadlock vira {@code 409 CONFLICT} com {@code Retry-After} e mensagem genérica, nunca 500,
 * venha ela direta, embrulhada ou só como SQLState do Postgres. Qualquer outra falha continua 500 sem vazar a causa.
 */
@DisplayName("Falha de trava/deadlock vira 409 CONFLICT (B2, S-215)")
class LockFailureMappingTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new Thrower()).setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new JacksonJsonHttpMessageConverter()).addFilters(new TraceIdFilter()).build();
    }

    private void expectConflict(String kind) throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.post("/boom/" + kind)).andExpect(status().isConflict())
                .andExpect(header().string("Retry-After", "1")).andExpect(jsonPath("$.code").value("CONFLICT"))
                .andExpect(jsonPath("$.traceId").exists())
                .andExpect(jsonPath("$.detail").value("A operação colidiu com outra em andamento. Tente de novo."));
    }

    @Test
    @DisplayName("CannotAcquireLockException, DeadlockLoserDataAccessException e PessimisticLockException são 409")
    void deveMapearAsExcecoesDeTrava() throws Exception {
        expectConflict("cannot-acquire");
        expectConflict("deadlock-loser");
        expectConflict("jpa-pessimistic");
    }

    @Test
    @DisplayName("deadlock embrulhado (JpaSystemException, TransactionSystemException) ou só o SQLState 40P01/55P03 também é 409")
    void deveMapearEmbrulhado() throws Exception {
        expectConflict("wrapped-sqlstate");
        expectConflict("tx-system");
        expectConflict("lock-not-available");
    }

    @Test
    @DisplayName("outra falha continua 500 INTERNAL_ERROR sem vazar a mensagem")
    void deveManterOutrasFalhasEm500() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.post("/boom/other")).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.detail").value("Erro inesperado. Informe o traceId ao suporte."));
        mockMvc.perform(MockMvcRequestBuilders.post("/boom/unique")).andExpect(status().isInternalServerError());
    }

    @RestController
    @RequestMapping("/boom")
    static class Thrower {

        @PostMapping("/{kind}")
        void boom(@org.springframework.web.bind.annotation.PathVariable String kind) {
            switch (kind) {
                case "cannot-acquire" -> throw new CannotAcquireLockException("lock");
                case "deadlock-loser" -> throw new DeadlockLoserDataAccessException("deadlock", new SQLException("x", "40P01"));
                case "jpa-pessimistic" -> throw new PessimisticLockException("lock");
                case "wrapped-sqlstate" -> throw new JpaSystemException(new RuntimeException("flush",
                        new SQLException("ERROR: deadlock detected", "40P01")));
                case "tx-system" -> throw new TransactionSystemException("commit", new RuntimeException(new SQLException("x", "40001")));
                case "lock-not-available" -> throw new RuntimeException("x", new SQLException("y", "55P03"));
                case "unique" -> throw new RuntimeException("x", new SQLException("duplicate", "23505"));
                default -> throw new IllegalStateException("segredo interno");
            }
        }
    }
}
