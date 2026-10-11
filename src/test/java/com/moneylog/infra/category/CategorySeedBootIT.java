package com.moneylog.infra.category;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.moneylog.util.IntegrationTest;
import com.moneylog.util.ItAuth;

@IntegrationTest
@DisplayName("Seed de categorias no boot (B2)")
class CategorySeedBootIT {

    @Autowired
    private CategorySeedRunner runner;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ItAuth auth;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("dono existente com categorias de sistema faltando: o próximo boot completa as 16; rodar de novo não duplica")
    void deveCompletarOSeedEmOutroBoot() throws Exception {
        UUID owner = auth.ownerId();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> jdbc.update("""
                delete from categories where user_id = ? and system
                  and id not in (select category_id from transactions where category_id is not null)""", owner));
        Integer missing = jdbc.queryForObject("select count(*) from categories where user_id = ? and system", Integer.class, owner);
        assertThat(missing).isLessThan(16);

        runner.run(null);
        runner.run(null);

        assertThat(jdbc.queryForObject("select count(*) from categories where user_id = ? and system", Integer.class, owner)).isEqualTo(16);
        assertThat(jdbc.queryForObject("select count(distinct system_key) from categories where user_id = ? and system", Integer.class, owner))
                .isEqualTo(16);
    }
}
