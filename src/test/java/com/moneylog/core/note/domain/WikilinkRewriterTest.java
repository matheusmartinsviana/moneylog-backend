package com.moneylog.core.note.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("WikilinkRewriter (T110): troca só o alvo e preserva o resto byte a byte")
class WikilinkRewriterTest {

    @Test
    @DisplayName("preserva alias, seção, embed e escapes; só o alvo muda")
    void devePreservarAliasESecao() {
        String text = "a [[2026-10]] b [[reviews/2026-10]] c [[2026-10|alias]] d [[2026-10#Resumo|x]] e ![[2026-10]] f [[ 2026-10 ]] g [[outra]]";
        String out = WikilinkRewriter.rewrite(text, 0, link -> link.target().endsWith("2026-10") ? link.target().replace("2026-10", "outubro") : null);
        assertThat(out).isEqualTo("a [[outubro]] b [[reviews/outubro]] c [[outubro|alias]] d [[outubro#Resumo|x]] e ![[outubro]] f [[ outubro ]] g [[outra]]");
    }

    @Test
    @DisplayName("T110 CA5: link dentro de código não é reescrito")
    void naoDeveReescreverCodigo() {
        String text = "[[x]] `[[x]]`\n```\n[[x]]\n```\n[[x]]";
        assertThat(WikilinkRewriter.rewrite(text, 0, link -> "y")).isEqualTo("[[y]] `[[x]]`\n```\n[[x]]\n```\n[[y]]");
    }

    @Test
    @DisplayName("sem mudança devolve o mesmo texto; alvo igual não conta; null mantém")
    void deveDevolverOMesmoSemMudanca() {
        String text = "[[a]] [[b]]";
        assertThat(WikilinkRewriter.rewrite(text, 0, link -> null)).isSameAs(text);
        assertThat(WikilinkRewriter.rewrite(text, 0, Wikilink::target)).isSameAs(text);
        assertThat(WikilinkRewriter.rewrite(text, 0, link -> link.target().equals("b") ? "c" : null)).isEqualTo("[[a]] [[c]]");
    }

    @Test
    @DisplayName("from preserva o frontmatter")
    void devePreservarOFrontmatter() {
        String text = "---\nrelated: \"[[x]]\"\n---\n[[x]]";
        assertThat(WikilinkRewriter.rewrite(text, Frontmatter.parse(text).bodyStart(), link -> "y")).isEqualTo("---\nrelated: \"[[x]]\"\n---\n[[y]]");
    }
}
