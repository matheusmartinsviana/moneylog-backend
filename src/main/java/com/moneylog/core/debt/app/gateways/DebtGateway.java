package com.moneylog.core.debt.app.gateways;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.moneylog.core.debt.domain.Debt;
import com.moneylog.core.debt.domain.DebtDirection;
import com.moneylog.core.debt.domain.DebtStatus;
import com.moneylog.core.sk.page.PageQuery;
import com.moneylog.core.sk.page.Slice;
import com.moneylog.core.sk.persistence.Versioned;

/**
 * Persistência de dívidas. A linha de {@code debts} guarda só os fatos (D1 §2.6): o status e o saldo são <b>derivados</b>
 * das quitações, que são as movimentações ativas ligadas à dívida; os lançamentos do reconhecimento e do perdão ficam no
 * razão. Ao ler, o gateway reidrata o agregado completo ({@code Debt.restore}). Todo método filtra pelo {@code userId}.
 */
public interface DebtGateway {

    /**
     * Insere uma dívida nova (versão 0). Só a linha: o razão (reconhecimento) é gravado por {@code LedgerWriteGateway}. Numa
     * dívida com movimentação, insira a dívida <b>antes</b> da movimentação (a movimentação aponta para ela).
     */
    Versioned<Debt> insert(Debt debt);

    /**
     * Grava a mudança de uma dívida existente (descrição, previsão, encerramento).
     *
     * @param expectedVersion a versão que o chamador leu
     * @throws com.moneylog.core.sk.exception.VersionConflictException se a versão gravada é outra
     */
    Versioned<Debt> update(Debt debt, long expectedVersion);

    /** Trava a linha da dívida ({@code SELECT ... FOR UPDATE}): quitações, perdão e estorno da mesma dívida se serializam. */
    void lockForUpdate(UUID userId, UUID id);

    /** Reidrata a dívida com as quitações (ativas e estornadas) e os lançamentos próprios. */
    Optional<Versioned<Debt>> findById(UUID userId, UUID id);

    /**
     * Só os ids das dívidas cuja origem é a movimentação, em ordem de id: o estorno os trava (em ordem) <b>antes</b> de ler as
     * dívidas, para a leitura seguinte enxergar o que uma operação concorrente confirmou (nada é carregado antes da trava).
     */
    List<UUID> findIdsByOriginTransaction(UUID userId, UUID transactionId);

    /** As dívidas cuja movimentação de origem é {@code transactionId} (a despesa dividida tem uma por parte). */
    List<Versioned<Debt>> findByOriginTransaction(UUID userId, UUID transactionId);

    /** Todas as dívidas das pessoas informadas (qualquer status), da mais recente para a mais antiga. */
    List<Versioned<Debt>> findByPersons(UUID userId, Collection<UUID> personIds);

    /**
     * Página de dívidas em {@code date desc, id desc}. Os filtros {@code status} e {@code overdue} usam a mesma regra
     * derivada de {@code Debt} (quitações ativas e relógio), calculada na consulta.
     */
    Slice<Versioned<Debt>> list(UUID userId, DebtFilter filter, LocalDate today, PageQuery page);

    /** Data de cada quitação (id da movimentação → data), para a leitura mostrar o histórico. */
    Map<UUID, LocalDate> settlementDates(UUID userId, Collection<UUID> transactionIds);

    /**
     * @param direction {@code null} = qualquer
     * @param status    {@code null} = qualquer
     * @param personId  {@code null} = qualquer
     * @param overdue   {@code true} = só atrasadas (previsão anterior a hoje e saldo em aberto); {@code null} = qualquer
     */
    record DebtFilter(DebtDirection direction, DebtStatus status, UUID personId, Boolean overdue) {
        public static DebtFilter all() {
            return new DebtFilter(null, null, null, null);
        }
    }
}
