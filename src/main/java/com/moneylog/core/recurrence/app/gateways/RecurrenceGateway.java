package com.moneylog.core.recurrence.app.gateways;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.moneylog.core.recurrence.domain.RecurringTransaction;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.page.PageQuery;
import com.moneylog.core.sk.page.Slice;
import com.moneylog.core.sk.persistence.Versioned;

/**
 * Persistência das recorrências (o modelo e as ocorrências puladas). Todo método filtra pelo {@code userId}; recorrência de outro
 * usuário não existe. A implementação JPA é {@code RecurrenceGatewayImpl} (Etapa 4, T79).
 *
 * <p>As ocorrências <b>realizadas</b> não são um dado gravado à parte: são as das movimentações <b>ativas</b> com a origem
 * {@code RECURRENCE} e o par {@code (recurrenceId, occurrenceDate)} (único entre as ativas, S-49,
 * {@code uq_transactions_recurrence_occurrence}). Por isso estornar a movimentação gerada devolve a ocorrência à lista de
 * pendentes sem ninguém precisar "desrealizar" nada, e a reidratação (via {@code RecurringTransaction.restore}) recebe as
 * realizadas já derivadas.
 */
public interface RecurrenceGateway {

    /** A movimentação gerada por uma ocorrência realizada: o id e o valor real (que pode diferir do modelo). */
    record RealizedOccurrence(UUID transactionId, Money amount) {
    }

    /**
     * Trava a linha da recorrência ({@code SELECT ... FOR UPDATE}) até o fim da transação: duas realizações da mesma
     * ocorrência se serializam aqui. Ordem global de travas: movimentações → <b>recorrência</b> → cartão → dívidas → contas por
     * id. Não faz nada se não existe ou é de outro usuário.
     */
    void lockForUpdate(UUID userId, UUID recurrenceId);

    /** A recorrência com as ocorrências realizadas (derivadas das movimentações ativas) e as puladas. */
    Optional<RecurringTransaction> findById(UUID userId, UUID recurrenceId);

    /** Como {@link #findById}, com a versão do lock otimista e os carimbos de tempo. */
    Optional<Versioned<RecurringTransaction>> findVersioned(UUID userId, UUID recurrenceId);

    /**
     * Grava o modelo, as ocorrências puladas e o estado ativo (insere se a recorrência é nova, senão atualiza) e devolve a versão
     * gravada. As realizadas não são gravadas por aqui (derivam das movimentações, ver acima).
     */
    Versioned<RecurringTransaction> save(RecurringTransaction recurrence);

    /** Todas as recorrências do usuário (ativas e desativadas), com as ocorrências realizadas e puladas: as fontes de compromisso. */
    List<RecurringTransaction> findAll(UUID userId);

    /** Ordem {@code description asc, id}; {@code active} nulo = todas. Sem {@code COUNT}. */
    Slice<Versioned<RecurringTransaction>> list(UUID userId, Boolean active, PageQuery page);

    /** Para cada recorrência, as ocorrências realizadas (data → movimentação ativa e valor real). */
    Map<UUID, Map<LocalDate, RealizedOccurrence>> realizedOccurrences(UUID userId, Collection<UUID> recurrenceIds);
}
