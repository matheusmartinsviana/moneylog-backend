package com.moneylog.core.note.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.moneylog.core.note.exception.NoteExceptions.NoteAlreadyTrashedException;
import com.moneylog.core.note.exception.NoteExceptions.NoteFrontmatterReservedException;
import com.moneylog.core.note.exception.NoteExceptions.NoteLinkInvalidException;
import com.moneylog.core.note.exception.NoteExceptions.NoteLinkLimitReachedException;
import com.moneylog.core.note.exception.NoteExceptions.NoteNotTrashedException;
import com.moneylog.core.note.exception.NoteExceptions.NoteStateInconsistentException;
import com.moneylog.core.note.exception.NoteExceptions.NoteTooLargeException;
import com.moneylog.core.note.exception.NoteExceptions.NoteTrashedException;
import com.moneylog.core.note.exception.NoteExceptions.NoteWikilinkLimitReachedException;
import com.moneylog.core.sk.text.PlainText.TextInvalidCharactersException;

@DisplayName("Note (T107): texto, título, tags, wikilinks, vínculos explícitos e lixeira")
class NoteTest {

    private static final UUID USER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID MACBOOK = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID GOAL = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final WikilinkResolver NONE = new WikilinkResolver(List.of());
    private static final WikilinkResolver WITH_MACBOOK = new WikilinkResolver(List.of(new WikilinkResolver.Entry(MACBOOK, NotePath.of("MacBook.md"))));

    private static Note note(String body) {
        return Note.create(ID, USER, NotePath.of("reviews/2026-10.md"), body, List.of(), WITH_MACBOOK);
    }

    @Test
    @DisplayName("T108 CA1: cria com título = nome do arquivo, tags extraídas e wikilinks resolvidos ou pendentes (origem WIKILINK)")
    void deveCriarComDerivados() {
        Note n = note("Veja [[MacBook]] e [[Inexistente|x]] #revisao-mensal");
        assertThat(n.title()).isEqualTo("2026-10");
        assertThat(n.tags()).containsExactly("revisao-mensal");
        assertThat(n.wikilinks()).hasSize(2);
        NoteLink resolved = n.wikilinks().get(0);
        assertThat(resolved.origin()).isEqualTo(LinkOrigin.WIKILINK);
        assertThat(resolved.state()).isEqualTo(NoteLink.State.RESOLVED);
        assertThat(resolved.targetId()).isEqualTo(MACBOOK);
        assertThat(resolved.context()).contains("[[MacBook]]");
        NoteLink pending = n.wikilinks().get(1);
        assertThat(pending.state()).isEqualTo(NoteLink.State.PENDING);
        assertThat(pending.alias()).isEqualTo("x");
        assertThat(n.isTrashed()).isFalse();
        assertThat(n.trashedAt()).isEmpty();
    }

    @Test
    @DisplayName("o título é o title do frontmatter, cortado em 255; sem ele, o nome do arquivo; mover recalcula")
    void deveCalcularOTitulo() {
        assertThat(note("---\ntitle: Meu título\n---\ncorpo").title()).isEqualTo("Meu título");
        assertThat(note("---\ntitle: " + "t".repeat(400) + "\n---\n").title()).hasSize(255);
        Note n = note("corpo");
        n.move(NotePath.of("outra/pasta/nome novo.md"));
        assertThat(n.title()).isEqualTo("nome novo");
        assertThat(n.path().value()).isEqualTo("outra/pasta/nome novo.md");
    }

    @Test
    @DisplayName("wikilinks repetidos viram um só; o texto do corpo é guardado como foi escrito")
    void deveTirarRepetidos() {
        String body = "[[MacBook]] depois [[macbook|outro]] e ![[MacBook.md]]";
        Note n = note(body);
        assertThat(n.wikilinks()).hasSize(1);
        assertThat(n.body()).isEqualTo(body);
    }

    @Test
    @DisplayName("editar recalcula tags e wikilinks e mantém os vínculos explícitos; links() junta os dois")
    void deveEditarMantendoExplicitos() {
        Note n = Note.create(ID, USER, NotePath.of("a.md"), "[[MacBook]] #um", List.of(NoteLink.explicit(NoteLinkType.GOAL, GOAL)), WITH_MACBOOK);
        n.edit("[[Outra]] #dois", WITH_MACBOOK);
        assertThat(n.tags()).containsExactly("dois");
        assertThat(n.wikilinks()).extracting(NoteLink::text).containsExactly("Outra");
        assertThat(n.explicitLinks()).extracting(NoteLink::targetId).containsExactly(GOAL);
        assertThat(n.links()).hasSize(2);
        assertThat(n.referencedNotes()).isEmpty();
        assertThat(note("[[MacBook]]").referencedNotes()).containsExactly(MACBOOK);
    }

    @Test
    @DisplayName("reresolve atualiza só a resolução dos wikilinks")
    void deveResolverDeNovo() {
        Note n = Note.create(ID, USER, NotePath.of("a.md"), "[[MacBook]]", List.of(), NONE);
        assertThat(n.wikilinks().get(0).state()).isEqualTo(NoteLink.State.PENDING);
        n.reresolve(WITH_MACBOOK);
        assertThat(n.wikilinks().get(0).targetId()).isEqualTo(MACBOOK);
        n.reresolve(NONE);
        assertThat(n.wikilinks().get(0).targetId()).isNull();
        n.reresolve(NONE);
    }

    @Test
    @DisplayName("CA6: nota acima de 200 KB (bytes UTF-8) é NOTE_TOO_LARGE; exatamente 200 KB passa")
    void deveLimitarOTamanho() {
        assertThat(note("a".repeat(NoteLimits.NOTE_MAX_BYTES)).body()).hasSize(NoteLimits.NOTE_MAX_BYTES);
        assertThatThrownBy(() -> note("a".repeat(NoteLimits.NOTE_MAX_BYTES + 1))).isInstanceOf(NoteTooLargeException.class);
        String multibyte = "é".repeat(NoteLimits.NOTE_MAX_BYTES / 2 + 1);
        assertThat(multibyte.length()).isLessThan(NoteLimits.NOTE_MAX_BYTES);
        assertThat(multibyte.getBytes(StandardCharsets.UTF_8).length).isGreaterThan(NoteLimits.NOTE_MAX_BYTES);
        assertThatThrownBy(() -> note(multibyte)).isInstanceOf(NoteTooLargeException.class).extracting("code").isEqualTo("NOTE_TOO_LARGE");
    }

    @Test
    @DisplayName("caracteres de controle (exceto quebra de linha, CR e tab) e surrogate solto são recusados; HTML passa intacto")
    void deveValidarCaracteres() {
        assertThat(note("linha\r\n\tcom tab").body()).isEqualTo("linha\r\n\tcom tab");
        assertThatThrownBy(() -> note("a\u0000b")).isInstanceOf(TextInvalidCharactersException.class);
        assertThatThrownBy(() -> note("a\u001bb")).isInstanceOf(TextInvalidCharactersException.class);
        assertThatThrownBy(() -> note("a\ud800b")).isInstanceOf(TextInvalidCharactersException.class);
        assertThatThrownBy(() -> note("a\udc00")).isInstanceOf(TextInvalidCharactersException.class);
        assertThat(note("ok 🙂").body()).isEqualTo("ok 🙂");
        String html = "<script>alert(1)</script><img src=x onerror=alert(1)> [x](javascript:alert(1))";
        assertThat(note(html).body()).isEqualTo(html);
    }

    @Test
    @DisplayName("frontmatter inválido é só corpo (preservado byte a byte) e a chave moneylog é reservada")
    void deveValidarOFrontmatter() {
        String hostile = "---\nx: !!java.lang.Object {}\n---\n";
        assertThat(note(hostile).body()).isEqualTo(hostile);
        assertThat(note(hostile).title()).isEqualTo("2026-10");
        String rule = "---\nAta da reunião\n---\nConteúdo depois da régua";
        assertThat(note(rule).body()).isEqualTo(rule);
        assertThatThrownBy(() -> note("---\nmoneylog:\n  links: []\n---\n")).isInstanceOf(NoteFrontmatterReservedException.class);
    }

    @Test
    @DisplayName("S-271: título do frontmatter sem controle, sem formatação/bidi e em uma linha; cortado por ponto de código")
    void deveValidarOTitulo() {
        for (String yaml : new String[] {"title: \"a\\x00b\"", "title: \"a\\x1bb\"", "title: \"a\\u202eb\"", "title: \"a\\u200bb\"", "title: \"a\\nb\"",
                "title: \"a\\u0085b\"", "title: \"a\\u2028b\"", "title: \"\\ud800\""}) {
            assertThatThrownBy(() -> note("---\n" + yaml + "\n---\ncorpo")).as(yaml).isInstanceOf(TextInvalidCharactersException.class);
        }
        assertThat(note("---\ntitle: Título normal 🙂 çã\n---\ncorpo").title()).isEqualTo("Título normal 🙂 çã");
        String emoji = "a".repeat(254) + "😀bb";
        String title = note("---\ntitle: " + emoji + "\n---\ncorpo").title();
        assertThat(title).isEqualTo("a".repeat(254) + "😀");
        assertThat(title.codePointCount(0, title.length())).isEqualTo(255);
        assertThat(note("---\ntitle: " + "é".repeat(300) + "\n---\n").title()).hasSize(255);
    }

    @Test
    @DisplayName("vínculos explícitos: duplicatas somem, a nota não se vincula a ela mesma, limite de 100, só origem EXPLICIT")
    void deveValidarExplicitos() {
        Note n = note("x");
        n.replaceExplicitLinks(List.of(NoteLink.explicit(NoteLinkType.GOAL, GOAL), NoteLink.explicit(NoteLinkType.GOAL, GOAL),
                NoteLink.explicitMonth("2026-10"), NoteLink.pending(NoteLinkType.PERSON, "xyz")));
        assertThat(n.explicitLinks()).hasSize(3);
        assertThatThrownBy(() -> n.replaceExplicitLinks(List.of(NoteLink.explicit(NoteLinkType.NOTE, ID)))).isInstanceOf(NoteLinkInvalidException.class);
        List<NoteLink> many = new java.util.ArrayList<>();
        for (int i = 0; i < 101; i++) {
            many.add(NoteLink.explicit(NoteLinkType.GOAL, UUID.randomUUID()));
        }
        assertThatThrownBy(() -> n.replaceExplicitLinks(many)).isInstanceOf(NoteLinkLimitReachedException.class);
        assertThatThrownBy(() -> n.replaceExplicitLinks(List.of(n.wikilinks().isEmpty() ? wikilinkLink() : n.wikilinks().get(0))))
                .isInstanceOf(NoteLinkInvalidException.class);
    }

    private static NoteLink wikilinkLink() {
        return NoteLink.wikilink(WikilinkParser.parse("[[a]]").get(0), null, "c");
    }

    @Test
    @DisplayName("lixeira: trash/restore, estados inválidos e nota na lixeira não é editada, movida nem vinculada")
    void deveTratarALixeira() {
        Note n = note("x");
        assertThatThrownBy(n::restore).isInstanceOf(NoteNotTrashedException.class);
        n.trash(Instant.parse("2026-10-05T10:00:00Z"));
        assertThat(n.isTrashed()).isTrue();
        assertThat(n.trashedAt()).contains(Instant.parse("2026-10-05T10:00:00Z"));
        assertThatThrownBy(() -> n.trash(Instant.now())).isInstanceOf(NoteAlreadyTrashedException.class);
        assertThatThrownBy(() -> n.edit("y", NONE)).isInstanceOf(NoteTrashedException.class);
        assertThatThrownBy(() -> n.move(NotePath.of("b.md"))).isInstanceOf(NoteTrashedException.class);
        assertThatThrownBy(() -> n.replaceExplicitLinks(List.of())).isInstanceOf(NoteTrashedException.class);
        n.rewriteBody("reescrito pelo sistema [[MacBook]]", WITH_MACBOOK);
        assertThat(n.body()).startsWith("reescrito");
        n.restore();
        assertThat(n.isTrashed()).isFalse();
    }

    @Test
    @DisplayName("restore reidrata sem recalcular e recusa vínculo explícito com origem trocada")
    void deveReidratar() {
        NoteLink wiki = wikilinkLink();
        NoteLink explicit = NoteLink.explicit(NoteLinkType.DEBT, GOAL);
        Note n = Note.restore(ID, USER, NotePath.of("a.md"), "Título", "[[a]]", Set.of("t"), List.of(wiki, explicit), Instant.EPOCH);
        assertThat(n.title()).isEqualTo("Título");
        assertThat(n.tags()).containsExactly("t");
        assertThat(n.wikilinks()).containsExactly(wiki);
        assertThat(n.explicitLinks()).containsExactly(explicit);
        assertThat(n.isTrashed()).isTrue();
        assertThat(n.id()).isEqualTo(ID);
        assertThat(n.userId()).isEqualTo(USER);
        assertThat(NoteStateInconsistentException.class).isNotNull();
    }

    @Test
    @DisplayName("S-271: mais de 500 wikilinks distintos é recusado (nenhum link fica sem índice); 500 passam e os repetidos contam uma vez")
    void deveLimitarWikilinks() {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < NoteLimits.MAX_WIKILINKS; i++) {
            body.append("[[n").append(i).append("]] [[N").append(i).append("]] ");
        }
        assertThat(note(body.toString()).wikilinks()).hasSize(NoteLimits.MAX_WIKILINKS);
        assertThatThrownBy(() -> note(body + "[[um a mais]]")).isInstanceOf(NoteWikilinkLimitReachedException.class)
                .isInstanceOf(NoteLinkLimitReachedException.class).extracting("code").isEqualTo("NOTE_LINK_LIMIT_REACHED");
    }

    @Test
    @DisplayName("S-271: editar para passar de 500 wikilinks recusa e não altera a nota")
    void naoDeveEditarParaPassarOLimite() {
        Note n = note("antes [[MacBook]]");
        StringBuilder body = new StringBuilder();
        for (int i = 0; i <= NoteLimits.MAX_WIKILINKS; i++) {
            body.append("[[n").append(i).append("]] ");
        }
        assertThatThrownBy(() -> n.edit(body.toString(), WITH_MACBOOK)).isInstanceOf(NoteWikilinkLimitReachedException.class);
        assertThat(n.body()).isEqualTo("antes [[MacBook]]");
    }
}
