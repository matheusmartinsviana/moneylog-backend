package com.moneylog.core.note.app;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.moneylog.core.note.app.gateways.NoteGateway;
import com.moneylog.core.note.domain.NotePath;
import com.moneylog.core.note.domain.WikilinkResolver;
import com.moneylog.core.note.exception.NoteExceptions.NotePathTakenException;

/** Operações sobre o índice do vault que os casos de uso de escrita compartilham. */
public final class VaultIndex {

    private VaultIndex() {
    }

    /**
     * Resolve de novo os wikilinks <b>afetados</b> e grava só o que mudou. Chamado depois de criar, mover, mandar para a lixeira, restaurar e
     * importar: um link pendente passa a resolver quando a nota aparece, e um resolvido volta a pendente quando ela sai. A resolução só
     * depende das notas de mesmo nome, então basta olhar os links cuja chave de nome coincide com a das notas que entraram, saíram ou
     * mudaram de nome ({@code affectedPaths}): o custo não cresce com o tamanho do vault (S-278).
     */
    public static void refreshWikilinks(NoteGateway notes, UUID userId, Collection<NotePath> affectedPaths) {
        refreshWikilinks(notes, userId, affectedPaths, Set.of());
    }

    /**
     * Como {@link #refreshWikilinks(NoteGateway, UUID, Collection)}, mais as chaves de nome dos wikilinks de notas recém-criadas sem índice
     * (a importação cria as notas sem resolver os links; os que apontam para notas que já existiam têm de ser resolvidos agora).
     */
    public static void refreshWikilinks(NoteGateway notes, UUID userId, Collection<NotePath> affectedPaths, Collection<String> extraNameKeys) {
        Set<String> keys = new HashSet<>(extraNameKeys);
        for (NotePath path : affectedPaths) {
            keys.add(WikilinkResolver.nameKey(path));
        }
        if (keys.isEmpty()) {
            return;
        }
        WikilinkResolver resolver = new WikilinkResolver(notes.activeIndex(userId));
        List<WikilinkUpdate> changes = new ArrayList<>();
        for (StoredWikilink stored : notes.wikilinksByName(userId, keys)) {
            UUID now = resolver.resolve(stored.text()).orElse(null);
            String key = stored.nameKey() != null ? stored.nameKey() : WikilinkResolver.nameKey(stored.text());
            if (!Objects.equals(now, stored.targetNoteId()) || stored.nameKey() == null) {
                changes.add(new WikilinkUpdate(stored.linkId(), now, key));
            }
        }
        if (!changes.isEmpty()) {
            notes.updateWikilinks(userId, changes);
        }
    }

    /** {@code NOTE_PATH_TAKEN} se outra nota ativa (diferente de {@code exceptId}) usa o caminho, sem diferenciar maiúsculas. */
    public static void requireFree(List<WikilinkResolver.Entry> index, NotePath path, UUID exceptId) {
        String key = path.key();
        for (WikilinkResolver.Entry entry : index) {
            if (!entry.id().equals(exceptId) && entry.path().key().equals(key)) {
                throw new NotePathTakenException(path.value());
            }
        }
    }
}
