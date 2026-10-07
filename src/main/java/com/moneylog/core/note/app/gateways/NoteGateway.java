package com.moneylog.core.note.app.gateways;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.moneylog.core.note.app.Backlink;
import com.moneylog.core.note.app.NoteQuery;
import com.moneylog.core.note.app.NoteSummary;
import com.moneylog.core.note.app.StoredWikilink;
import com.moneylog.core.note.app.TagCount;
import com.moneylog.core.note.app.WikilinkUpdate;
import com.moneylog.core.note.domain.Note;
import com.moneylog.core.note.domain.WikilinkResolver;
import com.moneylog.core.sk.page.PageQuery;
import com.moneylog.core.sk.page.Slice;
import com.moneylog.core.sk.persistence.Versioned;

/**
 * Persistência das notas, tags e vínculos. Todo método filtra pelo {@code userId}; id de outro usuário não existe.
 *
 * <p><b>Ordem de travas:</b> toda escrita do vault começa por {@link #lockVault} e não trava mais nada (as notas não participam das travas
 * financeiras: movimentações, recorrência, cartão, dívidas, pessoas, metas, contas). Por isso nunca formam ciclo com elas.
 */
public interface NoteGateway {

    /** Serializa as escritas do vault do usuário até o fim da transação (a resolução de wikilinks olha o vault inteiro). */
    void lockVault(UUID userId);

    /**
     * Insere a nota nova ou grava a existente (dados, tags e todos os vínculos). Toda gravação sobe a versão.
     *
     * @throws com.moneylog.core.sk.exception.VersionConflictException se outra requisição alterou antes
     */
    Versioned<Note> save(Note note);

    /** A nota com tags e vínculos, ativa ou na lixeira. */
    Optional<Versioned<Note>> findById(UUID userId, UUID noteId);

    List<Versioned<Note>> findByIds(UUID userId, Collection<UUID> noteIds);

    /** Id e caminho das notas ativas (índice de resolução dos wikilinks). */
    List<WikilinkResolver.Entry> activeIndex(UUID userId);

    /** Caminho das notas na lixeira por id (a exportação cita nota da lixeira nos vínculos explícitos, com o estado). */
    java.util.Map<UUID, String> trashedPaths(UUID userId);

    /** Notas do usuário, ativas e na lixeira (limite de 5.000). */
    int countAll(UUID userId);

    /**
     * As linhas de wikilink do usuário (de notas ativas e da lixeira) cujo alvo tem uma das chaves de nome dadas
     * ({@link WikilinkResolver#nameKey(String)}), mais as anteriores à chave (nome nulo), que se atualizam no caminho.
     */
    List<StoredWikilink> wikilinksByName(UUID userId, Collection<String> nameKeys);

    /** Grava a nova resolução e a chave de nome dos wikilinks. Não sobe a versão das notas. */
    void updateWikilinks(UUID userId, List<WikilinkUpdate> updates);

    /** Ids das notas (ativas ou na lixeira) que têm wikilink resolvido para {@code targetNoteId}. */
    List<UUID> noteIdsLinkingTo(UUID userId, UUID targetNoteId);

    /** Lista e busca (sem o corpo). */
    Slice<NoteSummary> search(NoteQuery query);

    /** Notas ativas que apontam para a nota (wikilink resolvido ou vínculo explícito NOTE), uma por nota de origem. */
    Slice<Backlink> backlinks(UUID userId, UUID noteId, PageQuery page);

    /** Tags das notas ativas com a contagem. */
    List<TagCount> tagCounts(UUID userId);

    /** Quantos vínculos (wikilink ou explícito) das notas dadas estão pendentes. */
    int countPendingLinks(UUID userId, Collection<UUID> noteIds);

    /** Apaga de vez as notas da lixeira (e seus vínculos). Devolve quantas. Única exclusão definitiva do vault. */
    int deleteTrashed(UUID userId);

    /** Notas ativas por ordem de id, a partir de {@code afterId} (exclusive; {@code null} = do começo): exportação em lotes. */
    List<Versioned<Note>> findActiveBatch(UUID userId, UUID afterId, int limit);
}
