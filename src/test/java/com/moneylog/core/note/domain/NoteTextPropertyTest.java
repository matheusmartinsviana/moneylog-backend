package com.moneylog.core.note.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.moneylog.core.note.domain.Frontmatter.LinkRef;

@DisplayName("Propriedades do texto da nota (T107 CA7): o que não é link nunca é reescrito")
class NoteTextPropertyTest {

    private static final String[] PIECES = {"texto simples ", "Revisão de outubro ", "\n", "\n\n", "\r\n", "\t", "#tag ", "# Título\n", "`[[em código]]` ",
            "```\n[[bloco]]\n```\n", "~~~\n#nao-tag [[x]]\n~~~\n", "[[MacBook]] ", "[[projetos/MG Tech|cliente]] ", "[[2026-10#Resumo]] ", "![[foto.png]] ",
            "[[]] ", "[x](http://a.com/#f) ", "<script>alert(1)</script> ", "🙂 ", "---\n", "...\n", "moneylog:\n", "  links: []\n", "a: b\n", "\\[[escapado]] ",
            "| [[a\\|b]] |\n", "[[ espaço ]] ", "é ã ç ", "[unclosed ", "]] "};

    private static String random(Random r) {
        StringBuilder text = new StringBuilder();
        if (r.nextInt(3) == 0) {
            text.append("---\n").append(r.nextBoolean() ? "title: T\ntags: [a, b]\n" : "").append("---\n");
        }
        int n = r.nextInt(25);
        for (int i = 0; i < n; i++) {
            text.append(PIECES[r.nextInt(PIECES.length)]);
        }
        return text.toString();
    }

    @Test
    @DisplayName("reescrita identidade e reescrita seguida da inversa devolvem o texto byte a byte (1.000 textos aleatórios)")
    void deveSerFielNaReescrita() {
        Random r = new Random(42);
        for (int i = 0; i < 1000; i++) {
            String text = random(r);
            int from = bodyStart(text);
            assertThat(WikilinkRewriter.rewrite(text, from, l -> null)).isEqualTo(text);
            assertThat(WikilinkRewriter.rewrite(text, from, Wikilink::target)).isEqualTo(text);
            String renamed = WikilinkRewriter.rewrite(text, from, l -> l.target().equals("MacBook") ? "Notebook" : null);
            String back = WikilinkRewriter.rewrite(renamed, from, l -> l.target().equals("Notebook") ? "MacBook" : null);
            assertThat(back).as(text).isEqualTo(text);
            assertThat(stripLinks(renamed, from)).isEqualTo(stripLinks(text, from));
        }
    }

    private static final String[] CODE_PIECES = {"`[[MacBook]]` ", "``a [[MacBook]] `b` c`` ", "\n```\n[[MacBook]] #tag\n```\n", "\n~~~js\n[[MacBook|x]]\n~~~\n",
            "\n\n    [[MacBook]] indentado\n    [[MacBook#s]]\n\n", "\n\n\t[[MacBook]] com tab\n\n"};
    private static final String[] PLAIN_PIECES = {"texto simples ", "Revisão ", "\n", "\r\n", "#tag ", "[[MacBook]] ", "[[MacBook]]\n", "[[projetos/MG Tech|cliente]] ",
            "[[macbook#Seção|apelido]] ", "![[MacBook]] ", "[[ MacBook ]] ", "\\[[MacBook]] ", "[x](http://a.com/#f) ", "<b>[[Outro]]</b> ", "🙂 "};

    @Test
    @DisplayName("M8: oráculo independente: renomear nunca toca o código (cercado, inline, indentado) e só troca o alvo fora dele (2.000 textos)")
    void deveNuncaReescreverCodigo() {
        Random r = new Random(2026);
        for (int i = 0; i < 2000; i++) {
            StringBuilder text = new StringBuilder();
            StringBuilder expected = new StringBuilder();
            int n = 1 + r.nextInt(14);
            for (int k = 0; k < n; k++) {
                boolean code = r.nextInt(3) == 0;
                String piece = code ? CODE_PIECES[r.nextInt(CODE_PIECES.length)] : PLAIN_PIECES[r.nextInt(PLAIN_PIECES.length)];
                text.append(piece);
                expected.append(code ? piece : oracleRename(piece));
            }
            String original = text.toString();
            String rewritten = WikilinkRewriter.rewrite(original, 0, l -> l.target().equalsIgnoreCase("MacBook") ? "Notebook" : null);
            assertThat(rewritten).as(original).isEqualTo(expected.toString());
            assertThat(WikilinkRewriter.rewrite(rewritten, 0, l -> l.target().equals("Notebook") ? "MacBook" : null)).as(original).isEqualTo(
                    original.replace("[[macbook#", "[[MacBook#"));
        }
    }

    private static String oracleRename(String piece) {
        return piece.replace("[[MacBook]]", "[[Notebook]]").replace("[[MacBook|", "[[Notebook|").replace("[[ MacBook ]]", "[[ Notebook ]]")
                .replace("[[macbook#", "[[Notebook#").replace("\\[[Notebook]]", "\\[[MacBook]]");
    }

    @Test
    @DisplayName("exportar e importar o texto (withMoneylogLinks + stripMoneylog) devolve o texto original byte a byte (1.000 textos aleatórios)")
    void deveSerFielNoRoundTripDoFrontmatter() {
        Random r = new Random(7);
        List<LinkRef> refs = List.of(new LinkRef("GOAL", "9b1d7c2e-0000-4000-8000-000000000001", null, null), new LinkRef("MONTHLY_SUMMARY", null, "2026-10", null));
        for (int i = 0; i < 1000; i++) {
            String text = random(r);
            if (text.contains("moneylog")) {
                continue;
            }
            String exported = Frontmatter.withMoneylogLinks(text, refs);
            assertThat(Frontmatter.parse(exported).moneylogLinks()).as(text).isEqualTo(refs);
            assertThat(Frontmatter.stripMoneylog(exported)).as(text).isEqualTo(text);
        }
    }

    @Test
    @DisplayName("o parser nunca falha nem devolve índices inválidos em textos aleatórios")
    void deveSerRobusto() {
        Random r = new Random(99);
        for (int i = 0; i < 2000; i++) {
            String text = random(r);
            for (Wikilink l : WikilinkParser.parse(text, bodyStart(text))) {
                assertThat(text.substring(l.targetStart(), l.targetEnd())).isEqualTo(l.target());
                assertThat(text.substring(l.start(), l.end())).contains("[[").endsWith("]]");
            }
            assertThat(NoteTags.extract(text, bodyStart(text), List.of(), List.of())).hasSizeLessThanOrEqualTo(NoteLimits.MAX_TAGS);
        }
    }

    private static int bodyStart(String text) {
        try {
            return Frontmatter.parse(text).bodyStart();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static String stripLinks(String text, int from) {
        List<Wikilink> links = WikilinkParser.parse(text, from);
        StringBuilder out = new StringBuilder();
        int cursor = 0;
        List<Wikilink> sorted = new ArrayList<>(links);
        for (Wikilink l : sorted) {
            out.append(text, cursor, l.targetStart());
            cursor = l.targetEnd();
        }
        return out.append(text.substring(cursor)).toString();
    }
}
