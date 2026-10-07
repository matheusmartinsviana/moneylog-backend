package com.moneylog.infra.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

/** B8 / BUG-QA-01: leituras com mais de uma consulta abrem a transação em REPEATABLE_READ, somente leitura. */
@DisplayName("Isolamento dos proxies transacionais (B8)")
class TransactionalProxiesIsolationTest {

    interface Reader {
        String read();
    }

    private static final class RecordingManager implements PlatformTransactionManager {
        final List<TransactionDefinition> definitions = new ArrayList<>();

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            definitions.add(definition);
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
        }

        @Override
        public void rollback(TransactionStatus status) {
        }
    }

    @Test
    @DisplayName("snapshotReadOnly: REPEATABLE_READ e readOnly; readOnly e required seguem no isolamento padrão")
    void deveAbrirOSnapshot() {
        RecordingManager snapshot = new RecordingManager();
        Supplier<Reader> target = () -> () -> "ok";
        TransactionalProxies.snapshotReadOnly(Reader.class, target.get(), snapshot).read();
        assertThat(snapshot.definitions.get(0).getIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        assertThat(snapshot.definitions.get(0).isReadOnly()).isTrue();

        RecordingManager plain = new RecordingManager();
        TransactionalProxies.readOnly(Reader.class, target.get(), plain).read();
        TransactionalProxies.required(Reader.class, target.get(), plain).read();
        assertThat(plain.definitions).extracting(TransactionDefinition::getIsolationLevel)
                .containsOnly(TransactionDefinition.ISOLATION_DEFAULT);
    }
}
