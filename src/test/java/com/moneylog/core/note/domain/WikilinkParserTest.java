package com.moneylog.core.note.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("WikilinkParser (T107, S-158): [[nome]], [[pasta/nome]], [[nome|alias]], [[nome#seção]]")
class WikilinkParserTest {

    @Test
    @DisplayName("CA2: acha 3 links (nome, caminho+alias, nome+seção) e ignora código inline e em bloco")
    void deveAcharTresLinksEIgnorarCodigo() {
        String body = "veja [[MacBook]], [[projetos/MG Tech|cliente]] e [[2026-10#Resumo]] mas não `[[em código]]` nem ```[[bloco]]```";
        List<Wikilink> links = WikilinkParser.parse(body);
        assertThat(links).hasSize(3);
        assertThat(links.get(0).target()).isEqualTo("MacBook");
        assertThat(links.get(0).alias()).isNull();
        assertThat(links.get(1).target()).isEqualTo("projetos/MG Tech");
        assertThat(links.get(1).alias()).isEqualTo("cliente");
        assertThat(links.get(1).isPathForm()).isTrue();
        assertThat(links.get(2).target()).isEqualTo("2026-10");
        assertThat(links.get(2).section()).isEqualTo("Resumo");
        assertThat(links.get(2).isPathForm()).isFalse();
    }

    @Test
    @DisplayName("os índices apontam para o texto original (início do link e do alvo)")
    void deveGuardarPosicoes() {
        String body = "ab [[ MacBook |alias]] cd";
        Wikilink link = WikilinkParser.parse(body).get(0);
        assertThat(body.substring(link.start(), link.end())).isEqualTo("[[ MacBook |alias]]");
        assertThat(body.substring(link.targetStart(), link.targetEnd())).isEqualTo("MacBook");
        assertThat(link.alias()).isEqualTo("alias");
    }

    @Test
    @DisplayName("embed ![[arquivo]] também é link (pendente se não for nota); o ! entra no início")
    void deveTratarEmbed() {
        String body = "foto ![[foto.png]] e ![[nota]]";
        List<Wikilink> links = WikilinkParser.parse(body);
        assertThat(links).hasSize(2);
        assertThat(links).allMatch(Wikilink::embed);
        assertThat(body.substring(links.get(0).start(), links.get(0).end())).isEqualTo("![[foto.png]]");
        assertThat(links.get(0).target()).isEqualTo("foto.png");
    }

    @Test
    @DisplayName("pipe escapado de tabela [[a\\|b]] e seção com alias [[a#s|b]]")
    void devePipeEscapado() {
        List<Wikilink> links = WikilinkParser.parse("| [[a\\|alias]] | [[b#sec|outro]] |");
        assertThat(links.get(0).target()).isEqualTo("a");
        assertThat(links.get(0).alias()).isEqualTo("alias");
        assertThat(links.get(1).target()).isEqualTo("b");
        assertThat(links.get(1).section()).isEqualTo("sec");
        assertThat(links.get(1).alias()).isEqualTo("outro");
    }

    @Test
    @DisplayName("não são links: alvo vazio ([[#seção]], [[]]), sem fechamento, com [ ] ou quebra de linha, escapados, alvo enorme")
    void deveIgnorarOQueNaoELink() {
        assertThat(WikilinkParser.parse("[[#so secao]] [[]] [[ ]] [[sem fechar ]x [[a\nb]] [[a[b]] \\[[escapado]]")).isEmpty();
        assertThat(WikilinkParser.parse("[[" + "x".repeat(256) + "]]")).isEmpty();
        assertThat(WikilinkParser.parse("[[" + "x".repeat(255) + "]]")).hasSize(1);
        assertThat(WikilinkParser.parse("")).isEmpty();
    }

    @Test
    @DisplayName("from pula o frontmatter")
    void devePularOFrontmatter() {
        String text = "---\nrelated: \"[[fora]]\"\n---\n[[dentro]]";
        int from = Frontmatter.parse(text).bodyStart();
        assertThat(WikilinkParser.parse(text, from)).extracting(Wikilink::target).containsExactly("dentro");
    }

    @Test
    @DisplayName("foldedTarget ignora caixa, acento, .md e barra inicial; hasMarkdownExtension")
    void deveDobrarOAlvo() {
        Wikilink link = WikilinkParser.parse("[[/Revisão/Outubro.MD]]").get(0);
        assertThat(link.foldedTarget()).isEqualTo("revisao/outubro");
        assertThat(link.hasMarkdownExtension()).isTrue();
        assertThat(WikilinkParser.parse("[[a]]").get(0).hasMarkdownExtension()).isFalse();
    }
}
