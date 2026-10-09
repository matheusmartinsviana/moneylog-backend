package com.moneylog.core.goal.app.gateways;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.moneylog.core.goal.domain.Goal;
import com.moneylog.core.sk.persistence.Versioned;

/**
 * Persistência de metas e das reservas delas. Todo método filtra pelo {@code userId}; id de outro usuário não existe.
 * Reservas só são acrescentadas (nunca editadas): {@link #save} grava as que ainda não existem.
 */
public interface GoalGateway {

    /**
     * Insere a meta nova ou grava a mudança de uma existente (dados e reservas novas), devolvendo-a com a versão gravada. Toda gravação
     * sobe a versão, inclusive a que só acrescenta reserva.
     *
     * @throws com.moneylog.core.sk.exception.VersionConflictException se outra requisição alterou antes
     */
    Versioned<Goal> save(Goal goal);

    /**
     * Trava a linha da meta ({@code SELECT ... FOR UPDATE}) até o fim da transação: duas reservas na mesma meta se serializam aqui.
     * Chame <b>antes</b> de ler a meta e das travas de conta (a meta vem antes das contas na ordem global). Não faz nada se a meta não
     * existe ou é de outro usuário.
     */
    void lockForUpdate(UUID userId, UUID goalId);

    Optional<Versioned<Goal>> findById(UUID userId, UUID goalId);

    /** Todas as metas do usuário (com reservas), sem ordem garantida. */
    List<Versioned<Goal>> findAll(UUID userId);

    /** A meta em destaque do usuário, se houver (no máximo uma; ADR-018). */
    Optional<Versioned<Goal>> findFeatured(UUID userId);

    /**
     * Serializa, até o fim da transação, as mudanças de destaque e de cota de fotos do usuário (advisory lock): duas requisições
     * concorrentes não passam da cota nem deixam duas metas em destaque. Chame depois da trava da meta.
     */
    void lockUserMedia(UUID userId);

    /** Fotos e bytes guardados do usuário em todas as metas (para a cota). */
    PhotoUsage photoUsage(UUID userId);

    /** @param count fotos do usuário; @param bytes soma de exibição + miniatura */
    record PhotoUsage(int count, long bytes) {
    }
}
