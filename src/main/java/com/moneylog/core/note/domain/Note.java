package com.moneylog.core.note.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.moneylog.core.note.exception.NoteExceptions.NoteAlreadyTrashedException;
import com.moneylog.core.note.exception.NoteExceptions.NoteLinkInvalidException;
import com.moneylog.core.note.exception.NoteExceptions.NoteLinkLimitReachedException;
import com.moneylog.core.note.exception.NoteExceptions.NoteNotTrashedException;
import com.moneylog.core.note.exception.NoteExceptions.NoteStateInconsistentException;
import com.moneylog.core.note.exception.NoteExceptions.NoteWikilinkLimitReachedException;
import com.moneylog.core.note.exception.NoteExceptions.NoteTrashedException;

/**
 * Nota do vault (D1 §5, S-157 a S-166): texto Markdown em um caminho, com tags, wikilinks e vínculos explícitos.
 *
 * <ul>
 *   <li>O texto é guardado <b>como foi escrito</b> (frontmatter incluído, exceto a chave reservada {@code moneylog}); tags, título e
 *       wikilinks são derivados dele a cada gravação.</li>
 *   <li>Os wikilinks são recalculados quando o texto muda; os vínculos explícitos só mudam por {@link #replaceExplicitLinks}.</li>
 *   <li>Lixeira ({@code trashedAt}): a nota não é editada, movida nem vinculada até ser restaurada.</li>
 *   <li>Nada aqui toca razão, saldo ou qualquer cálculo financeiro.</li>
 * </ul>
 */
public final class Note {

    private final UUID id;
    private final UUID userId;
    private NotePath path;
    private String title;
    private String body;
    private Set<String> tags;
    private final List<NoteLink> wikilinks = new ArrayList<>();
    private final List<NoteLink> explicitLinks = new ArrayList<>();
    private Instant trashedAt;

    private Note(UUID id, UUID userId, NotePath path, Instant trashedAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.userId = Objects.requireNonNull(userId, "userId");
        this.path = Objects.requireNonNull(path, "path");
        this.trashedAt = trashedAt;
    }

    public static Note create(UUID id, UUID userId, NotePath path, String body, List<NoteLink> explicit, WikilinkResolver resolver) {
        Note note = new Note(id, userId, path, null);
        note.applyBody(body, resolver);
        note.setExplicit(explicit);
        return note;
    }

    /** Reidratação: confia no que foi gravado, mas recusa vínculo de origem trocada. */
    public static Note restore(UUID id, UUID userId, NotePath path, String title, String body, Set<String> tags, List<NoteLink> links,
                               Instant trashedAt) {
        Note note = new Note(id, userId, path, trashedAt);
        note.title = Objects.requireNonNull(title, "title");
        note.body = Objects.requireNonNull(body, "body");
        note.tags = new LinkedHashSet<>(tags);
        for (NoteLink link : links) {
            (link.origin() == LinkOrigin.WIKILINK ? note.wikilinks : note.explicitLinks).add(link);
        }
        if (note.explicitLinks.stream().anyMatch(l -> l.origin() != LinkOrigin.EXPLICIT)) {
            throw new NoteStateInconsistentException("vínculo de origem inesperada na nota " + id);
        }
        return note;
    }

    // ------------------------------------------------------------------ comandos

    /** Edição do usuário. */
    public void edit(String newBody, WikilinkResolver resolver) {
        ensureNotTrashed();
        applyBody(newBody, resolver);
    }

    /**
     * Reescrita pelo sistema (renomear outra nota): vale também na lixeira, para a nota restaurada não ficar com link velho.
     */
    public void rewriteBody(String newBody, WikilinkResolver resolver) {
        applyBody(newBody, resolver);
    }

    public void move(NotePath newPath) {
        ensureNotTrashed();
        this.path = Objects.requireNonNull(newPath, "newPath");
        this.title = NoteContent.analyze(body).titleFor(path);
    }

    public void trash(Instant now) {
        if (trashedAt != null) {
            throw new NoteAlreadyTrashedException();
        }
        this.trashedAt = Objects.requireNonNull(now, "now");
    }

    public void restore() {
        if (trashedAt == null) {
            throw new NoteNotTrashedException();
        }
        this.trashedAt = null;
    }

    /** Troca todos os vínculos explícitos (já validados contra os alvos). Duplicatas somem; o limite é de 100. */
    public void replaceExplicitLinks(List<NoteLink> links) {
        ensureNotTrashed();
        setExplicit(links);
    }

    /** Resolve de novo os wikilinks do corpo contra o índice atual (nota criada, movida, apagada ou restaurada). */
    public void reresolve(WikilinkResolver resolver) {
        for (int i = 0; i < wikilinks.size(); i++) {
            NoteLink link = wikilinks.get(i);
            UUID resolved = resolver.resolve(link.text()).orElse(null);
            if (!Objects.equals(resolved, link.targetId())) {
                wikilinks.set(i, link.withTarget(resolved));
            }
        }
    }

    // ------------------------------------------------------------------ interno

    private void applyBody(String newBody, WikilinkResolver resolver) {
        NoteContent content = NoteContent.analyze(newBody);
        // distintos pelo alvo (sem .md, caixa nem acento), na ordem do texto; mais que o limite é recusado (nenhum link fica sem índice)
        Map<String, Wikilink> distinct = new LinkedHashMap<>();
        for (Wikilink link : content.wikilinks()) {
            distinct.putIfAbsent(NoteLink.wikilinkIdentity(link.target()), link);
            if (distinct.size() > NoteLimits.MAX_WIKILINKS) {
                throw new NoteWikilinkLimitReachedException(NoteLimits.MAX_WIKILINKS);
            }
        }
        List<NoteLink> links = new ArrayList<>();
        for (Wikilink link : distinct.values()) {
            UUID resolved = resolver.resolve(link).orElse(null);
            links.add(NoteLink.wikilink(link, resolved, NoteSnippets.around(newBody, link.start(), link.end(), NoteLimits.SNIPPET_MAX)));
        }
        // só depois de tudo validado a nota muda (uma recusa nunca deixa a nota pela metade)
        this.body = newBody;
        this.title = content.titleFor(path);
        this.tags = new LinkedHashSet<>(content.tags());
        this.wikilinks.clear();
        this.wikilinks.addAll(links);
    }

    private void setExplicit(List<NoteLink> links) {
        Map<String, NoteLink> distinct = new LinkedHashMap<>();
        for (NoteLink link : links) {
            if (link.origin() != LinkOrigin.EXPLICIT) {
                throw new NoteLinkInvalidException("só vínculos explícitos entram aqui.");
            }
            if (link.type() == NoteLinkType.NOTE && id.equals(link.targetId())) {
                throw new NoteLinkInvalidException("a nota não pode se vincular a ela mesma.");
            }
            distinct.putIfAbsent(link.identity(), link);
        }
        if (distinct.size() > NoteLimits.MAX_EXPLICIT_LINKS) {
            throw new NoteLinkLimitReachedException(NoteLimits.MAX_EXPLICIT_LINKS);
        }
        this.explicitLinks.clear();
        this.explicitLinks.addAll(distinct.values());
    }

    private void ensureNotTrashed() {
        if (trashedAt != null) {
            throw new NoteTrashedException();
        }
    }

    // ------------------------------------------------------------------ leitura

    public UUID id() {
        return id;
    }

    public UUID userId() {
        return userId;
    }

    public NotePath path() {
        return path;
    }

    public String title() {
        return title;
    }

    public String body() {
        return body;
    }

    public Set<String> tags() {
        return Set.copyOf(tags);
    }

    public List<NoteLink> wikilinks() {
        return List.copyOf(wikilinks);
    }

    public List<NoteLink> explicitLinks() {
        return List.copyOf(explicitLinks);
    }

    /** Wikilinks seguidos dos vínculos explícitos. */
    public List<NoteLink> links() {
        List<NoteLink> all = new ArrayList<>(wikilinks);
        all.addAll(explicitLinks);
        return List.copyOf(all);
    }

    public Optional<Instant> trashedAt() {
        return Optional.ofNullable(trashedAt);
    }

    public boolean isTrashed() {
        return trashedAt != null;
    }

    /** Ids das notas que este texto referencia (wikilinks resolvidos e vínculos NOTE). */
    public Collection<UUID> referencedNotes() {
        Set<UUID> ids = new LinkedHashSet<>();
        links().stream().filter(l -> l.type() == NoteLinkType.NOTE && l.targetId() != null).forEach(l -> ids.add(l.targetId()));
        return ids;
    }
}
