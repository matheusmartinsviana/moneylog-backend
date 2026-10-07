package com.moneylog.core.finance.app.transactions.handlers;

import java.util.List;
import java.util.UUID;

import com.moneylog.core.finance.app.transactions.usecase.CreateTransactionCommand;
import com.moneylog.core.transaction.domain.Transaction;
import com.moneylog.core.transaction.domain.TransactionOrigin;

/**
 * Um tipo de movimentação no caminho de criação e de edição (S-127): sabe quais travas o comando exige e como montar o
 * agregado. O despacho por tipo (registro por classe do comando) vive em {@link TransactionHandlers}; as Etapas 4 e 7
 * só acrescentam handlers, sem tocar nos existentes.
 *
 * <p><b>Ordem global de travas</b> (a mesma para todo caso de uso que escreve): linhas de movimentação
 * ({@link #transactionsToLock}) → cartão ({@link #cardToLock}) → dívidas e pessoas ({@link #debtsToLock},
 * {@link #personsToLock}, Etapa 4) → contas em ordem de id ({@link #accountsToLock}). A fábrica executa nessa ordem; um
 * handler só declara o que precisa.
 *
 * @param <C> o comando que o handler atende
 */
public interface TransactionTypeHandler<C extends CreateTransactionCommand> {

    Class<C> commandType();

    /** Movimentações a travar antes de qualquer leitura (ex.: a despesa original do reembolso, B1). */
    default List<UUID> transactionsToLock(C command) {
        return List.of();
    }

    /** O cartão cuja cadeia de faturas o comando escreve, ou {@code null} (ADR-006 §5). */
    default UUID cardToLock(C command) {
        return null;
    }

    /** Dívidas que o comando altera (quitação): travadas depois do cartão e antes das contas (ordem global estendida, Etapa 4). */
    default List<UUID> debtsToLock(C command) {
        return List.of();
    }

    /** Pessoas que recebem dívida nova com o comando: travadas junto das dívidas (arquivar e criar dívida se serializam). */
    default List<UUID> personsToLock(C command) {
        return List.of();
    }

    /** Contas que o comando toca (a fábrica ordena por id e acrescenta as da movimentação editada). */
    default List<UUID> accountsToLock(C command) {
        return List.of();
    }

    /**
     * Monta a movimentação. Não trava nada (a fábrica trava antes) e não grava.
     *
     * @param editing a movimentação que o comando substitui, ou {@code null} na criação
     */
    Transaction build(C command, UUID id, TransactionOrigin origin, Transaction editing);

    /**
     * Monta a movimentação <b>e</b> as dívidas novas que nascem com ela. O padrão não cria dívida; os handlers de dívida e a
     * despesa dividida sobrescrevem.
     */
    default BuiltTransaction buildWithEffects(C command, UUID id, TransactionOrigin origin, Transaction editing) {
        return BuiltTransaction.of(build(command, id, origin, editing));
    }
}
