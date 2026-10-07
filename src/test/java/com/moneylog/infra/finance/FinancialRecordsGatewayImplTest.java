package com.moneylog.infra.finance;

import static com.moneylog.core.util.Ids.USER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.moneylog.core.debt.exception.DebtExceptions.DebtStateInconsistentException;
import com.moneylog.core.finance.domain.FinancialRecords;
import com.moneylog.infra.account.persistence.AccountRepositoryJpa;
import com.moneylog.infra.card.persistence.BillingRuleRepositoryJpa;
import com.moneylog.infra.card.persistence.CardRepositoryJpa;
import com.moneylog.infra.debt.gateways.DebtHydrator;
import com.moneylog.infra.debt.persistence.DebtRepositoryJpa;
import com.moneylog.infra.finance.gateways.FinancialRecordsGatewayImpl;
import com.moneylog.infra.goal.persistence.GoalRepositoryJpa;
import com.moneylog.infra.goal.persistence.GoalReservationRepositoryJpa;
import com.moneylog.infra.ledger.persistence.LedgerPostingRepositoryJpa;
import com.moneylog.infra.transaction.gateways.TransactionHydrator;
import com.moneylog.infra.transaction.persistence.TransactionRepositoryJpa;

/**
 * BUG-QA-26: uma escrita de dívida que confirma no meio da leitura do razão (em {@code READ COMMITTED}, num caso de uso de escrita) aparece
 * como estado inconsistente. A leitura é refeita (limpando o contexto de persistência) e, se persistir, vira falha de trava (409), nunca 500;
 * em {@code REPEATABLE_READ} (instantâneo único) a inconsistência é dado corrompido e propaga (fail-closed, S-94).
 */
@DisplayName("Carregamento dos registros: leitura inconsistente sob escrita concorrente (BUG-QA-26)")
class FinancialRecordsGatewayImplTest {

    private final DebtHydrator debtHydrator = mock(DebtHydrator.class);
    private final EntityManager entityManager = mock(EntityManager.class);
    private final DebtRepositoryJpa debts = mock(DebtRepositoryJpa.class);
    private final FinancialRecordsGatewayImpl gateway = new FinancialRecordsGatewayImpl(mock(AccountRepositoryJpa.class),
            mock(TransactionRepositoryJpa.class), mock(LedgerPostingRepositoryJpa.class), mock(CardRepositoryJpa.class),
            mock(BillingRuleRepositoryJpa.class), mock(TransactionHydrator.class), debts, debtHydrator, mock(GoalRepositoryJpa.class),
            mock(GoalReservationRepositoryJpa.class), entityManager);

    @AfterEach
    void clearTransactionState() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
        TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(null);
    }

    @Test
    @DisplayName("sem instantâneo: a leitura inconsistente é refeita (com o contexto limpo) e a segunda tentativa vale")
    void deveRefazerALeituraTransitoria() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        when(debtHydrator.loadAll(any(), any())).thenThrow(new DebtStateInconsistentException("perdão sem lançamento"))
                .thenThrow(new DebtStateInconsistentException("perdão sem lançamento")).thenReturn(List.of());

        FinancialRecords records = gateway.load(USER_ID);

        assertThat(records.debts()).isEmpty();
        verify(debtHydrator, times(3)).loadAll(any(), any());
        verify(entityManager, times(2)).clear();
    }

    @Test
    @DisplayName("sem instantâneo e ainda inconsistente depois de todas as tentativas: falha de trava transitória (vira 409 CONFLICT com Retry-After), não 500")
    void deveVirarFalhaTransitoria() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        when(debtHydrator.loadAll(any(), any())).thenThrow(new DebtStateInconsistentException("perdão sem lançamento"));

        assertThatThrownBy(() -> gateway.load(USER_ID)).isInstanceOf(CannotAcquireLockException.class)
                .hasCauseInstanceOf(DebtStateInconsistentException.class);
        verify(debtHydrator, times(4)).loadAll(any(), any());
    }

    @Test
    @DisplayName("fora de transação a inconsistência propaga como está (dado corrompido), sem refazer")
    void devePropagarForaDeTransacao() {
        when(debtHydrator.loadAll(any(), any())).thenThrow(new DebtStateInconsistentException("dado corrompido"));
        assertThatThrownBy(() -> gateway.load(USER_ID)).isInstanceOf(DebtStateInconsistentException.class);
        verify(debtHydrator, times(1)).loadAll(any(), any());
    }

    @Test
    @DisplayName("em REPEATABLE_READ (instantâneo único) a inconsistência é dado corrompido: propaga na primeira tentativa, sem refazer nem limpar o contexto")
    void deveFalharSemRefazerNoInstantaneo() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        when(debtHydrator.loadAll(any(), any())).thenThrow(new DebtStateInconsistentException("dado corrompido"));

        assertThatThrownBy(() -> gateway.load(USER_ID)).isInstanceOf(DebtStateInconsistentException.class);
        verify(debtHydrator, times(1)).loadAll(any(), any());
        verify(entityManager, never()).clear();
    }
}
