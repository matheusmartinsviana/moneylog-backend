package com.moneylog.core.note.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import com.moneylog.core.note.exception.NoteExceptions.NotePathInvalidException;
import com.moneylog.core.note.exception.NoteExceptions.PathProblem;

@DisplayName("NotePath (T107, S-162): caminho do vault, válido por construção")
class NotePathTest {

    @Test
    @DisplayName("CA1: reviews/2026-10.md é válido; nome, pasta, caminho sem extensão e chave de unicidade")
    void deveAceitarCaminhoValido() {
        NotePath path = NotePath.of("reviews/2026-10.md");
        assertThat(path.value()).isEqualTo("reviews/2026-10.md");
        assertThat(path.name()).isEqualTo("2026-10");
        assertThat(path.folder()).isEqualTo("reviews");
        assertThat(path.withoutExtension()).isEqualTo("reviews/2026-10");
        assertThat(NotePath.of("MacBook.md").folder()).isEmpty();
    }

    @Test
    @DisplayName("CA1: Reviews/2026-10.md e reviews/2026-10.md colidem (mesma chave), mas são caminhos diferentes")
    void deveColidirSemDiferenciarMaiusculas() {
        NotePath upper = NotePath.of("Reviews/2026-10.md");
        NotePath lower = NotePath.of("reviews/2026-10.md");
        assertThat(upper.key()).isEqualTo(lower.key());
        assertThat(upper).isNotEqualTo(lower);
    }

    @ParameterizedTest(name = "[{index}] \"{0}\" é recusado")
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "../x.md", "a/../x.md", "/x.md", "a\\b.md", "x", "x.txt", ".md", "a/.md", "a//b.md", "a/b/", "x.md/",
            "C:/x.md", "c:x.md", "a/./b.md", ".hidden/x.md", "a/.x.md", "con.md", "a/NUL.md", "Com1.md", "lpt9.txt.md", "a<b.md", "a:b.md",
            "a|b.md", "a?b.md", "a*b.md", "a\"b.md", "a#b.md", "a^b.md", "a[b].md", "a]b.md", " x.md", "a /b.md", "a./b.md",
            "x\u0000.md", "x\n.md", "x\t.md", "x\u001b.md", "x\u202e.md", "x\u200b.md", "x\ufeff.md", "x\u2028.md", "\ud800.md"})
    void deveRecusarCaminhoInvalido(String raw) {
        assertThatThrownBy(() -> NotePath.of(raw)).isInstanceOf(NotePathInvalidException.class).extracting("code").isEqualTo("NOTE_PATH_INVALID");
        assertThat(NotePath.isValid(raw)).isFalse();
    }

    @Test
    @DisplayName("CA1: o limite de 255 caracteres e de 10 níveis")
    void deveLimitarTamanhoEProfundidade() {
        String ok = "a".repeat(255 - 3) + ".md";
        assertThat(NotePath.of(ok).value()).hasSize(255);
        assertThatThrownBy(() -> NotePath.of("a".repeat(253) + ".md")).isInstanceOf(NotePathInvalidException.class)
                .satisfies(e -> assertThat(((NotePathInvalidException) e).problem()).isEqualTo(PathProblem.TOO_LONG));
        assertThat(NotePath.of("a/b/c/d/e/f/g/h/i/j.md").value()).isEqualTo("a/b/c/d/e/f/g/h/i/j.md");
        assertThatThrownBy(() -> NotePath.of("a/b/c/d/e/f/g/h/i/j/k.md")).isInstanceOf(NotePathInvalidException.class)
                .satisfies(e -> assertThat(((NotePathInvalidException) e).problem()).isEqualTo(PathProblem.TOO_DEEP));
    }

    @Test
    @DisplayName("tentativas de escapar da pasta são marcadas como hostis; nome sem suporte não")
    void deveMarcarOHostil() {
        assertThat(problem("../../etc/passwd.md")).isEqualTo(PathProblem.TRAVERSAL);
        assertThat(problem("/etc/passwd.md")).isEqualTo(PathProblem.ABSOLUTE);
        assertThat(problem("a\\b.md")).isEqualTo(PathProblem.BACKSLASH);
        assertThat(problem("a\u0000b.md")).isEqualTo(PathProblem.CONTROL_CHARACTERS);
        assertThat(problem("C:/x.md")).isEqualTo(PathProblem.DRIVE_LETTER);
        // letra + dois-pontos que não é caminho de unidade ("Q: dúvida.md") é só caractere proibido: não é hostil
        assertThat(problem("Q: dúvida.md")).isEqualTo(PathProblem.FORBIDDEN_CHARACTER);
        assertThat(PathProblem.FORBIDDEN_CHARACTER.hostile()).isFalse();
        assertThat(PathProblem.TRAVERSAL.hostile() && PathProblem.ABSOLUTE.hostile() && PathProblem.BACKSLASH.hostile()
                && PathProblem.CONTROL_CHARACTERS.hostile() && PathProblem.DRIVE_LETTER.hostile()).isTrue();
        assertThat(problem("con.md")).isEqualTo(PathProblem.RESERVED_NAME);
        for (String reserved : new String[] {"CON .md", "pasta/aux  .md", "COM\u00b9.md", "com\u00b2.md", "LPT\u00b3.md", "lpt\u00b9 .md"}) {
            assertThat(problem(reserved)).as(reserved).isEqualTo(PathProblem.RESERVED_NAME);
        }
        assertThat(problem(".trash/x.md")).isEqualTo(PathProblem.HIDDEN_SEGMENT);
        assertThat(problem("x.txt")).isEqualTo(PathProblem.EXTENSION);
        assertThat(PathProblem.RESERVED_NAME.hostile()).isFalse();
    }

    @Test
    @DisplayName("Unicode vira NFC (o macOS gera NFD) e a extensão .MD é normalizada para .md")
    void deveNormalizar() {
        String nfd = "Revisa\u0303o.md";
        String nfc = "Revisão.md";
        assertThat(NotePath.of(nfd).value()).isEqualTo(NotePath.of(nfc).value());
        assertThat(NotePath.of("Nota.MD").value()).isEqualTo("Nota.md");
        assertThat(NotePath.of("emoji 🙂.md").name()).isEqualTo("emoji 🙂");
    }

    @Test
    @DisplayName("withName troca o nome e mantém a pasta")
    void deveTrocarONome() {
        assertThat(NotePath.of("reviews/2026-10.md").withName("outubro").value()).isEqualTo("reviews/outubro.md");
        assertThat(NotePath.of("a.md").withName("b").value()).isEqualTo("b.md");
        assertThat(NotePath.of("a/b.md").hashCode()).isEqualTo(NotePath.of("a/b.md").hashCode());
        assertThat(NotePath.of("a/b.md")).hasToString("a/b.md");
        assertThat(NotePath.of("Á/Ç.md").foldedWithoutExtension()).isEqualTo("a/c");
    }

    private static PathProblem problem(String raw) {
        try {
            NotePath.of(raw);
            return null;
        } catch (NotePathInvalidException e) {
            return e.problem();
        }
    }
}
