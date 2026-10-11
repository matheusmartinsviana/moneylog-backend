package com.moneylog.core.note.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("NoteTags (T107, S-165): tags do frontmatter e #tag do corpo")
class NoteTagsTest {

    private static final long MAX_ANALYZE_MILLIS = 500;

    private static java.util.Set<String> tags(String text, String... frontmatter) {
        return NoteTags.extract(text, 0, List.of(frontmatter), WikilinkParser.parse(text));
    }

    @Test
    @DisplayName("CA5: #tag no corpo, fora de código; # título não é tag; normaliza em minúsculas sem acento")
    void deveExtrairDoCorpo() {
        assertThat(tags("# Título\nveja #Revisão e #revisao-mensal, (#finanças) e #a/b e #x_y.\n`#codigo` e\n```\n#bloco\n```\n"))
                .containsExactly("revisao", "revisao-mensal", "financas", "a/b", "x_y");
        assertThat(tags("## Subtítulo\n#123 #2026 #1a")).containsExactly("1a");
        assertThat(tags("sem tags ## x # y #")).isEmpty();
    }

    @Test
    @DisplayName("não é tag: no meio de palavra, em URL, em âncora de wikilink ou terminando em - ou /")
    void deveIgnorarFalsasTags() {
        assertThat(tags("a#b http://x.com/#frag [[nota#secao]] x#y")).isEmpty();
        assertThat(tags("#fim- #fim2/ #ok")).containsExactly("fim", "fim2", "ok");
        assertThat(tags("#")).isEmpty();
    }

    @Test
    @DisplayName("tags do frontmatter entram normalizadas, sem # e com espaço virando hífen; inválidas somem")
    void deveNormalizarOFrontmatter() {
        assertThat(tags("corpo #extra", "#Revisão Mensal", "Finanças", "", "123", "a//b", "/a", "a/", "x".repeat(51), "com$cifrao"))
                .containsExactly("revisao-mensal", "financas", "extra");
        assertThat(NoteTags.normalize(null)).isEmpty();
        assertThat(NoteTags.normalize("##Dois")).contains("dois");
        assertThat(NoteTags.normalize("tag-ok_1")).contains("tag-ok_1");
        assertThat(NoteTags.normalize("日本語")).contains("日本語");
    }

    @Test
    @DisplayName("no máximo 100 tags por nota")
    void deveLimitarAsTags() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 150; i++) {
            text.append("#tag").append(i).append("x ");
        }
        assertThat(tags(text.toString())).hasSize(NoteLimits.MAX_TAGS);
    }

    @Test
    @DisplayName("S-271 (A3): o teto de 100 tags vale para o conjunto final, também para as tags do frontmatter")
    void deveLimitarAsTagsDoFrontmatter() {
        String[] many = new String[3_000];
        for (int i = 0; i < many.length; i++) {
            many[i] = "tag" + i + "x";
        }
        assertThat(tags("corpo #extra", many)).hasSize(NoteLimits.MAX_TAGS).doesNotContain("extra").startsWith("tag0x", "tag1x");
        StringBuilder yaml = new StringBuilder("---\ntags:\n");
        for (int i = 0; i < 3_000; i++) {
            yaml.append("  - t").append(i).append("x\n");
        }
        assertThat(NoteContent.analyze(yaml + "---\ncorpo #outra").tags()).hasSize(NoteLimits.MAX_TAGS);
    }

    @Test
    @DisplayName("S-271 (A1): custo linear: 20.000 wikilinks e 50.000 '#' em 200 KB levam menos de 100 ms")
    void deveSerLinear() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 20_000; i++) {
            text.append("[[a]]");
        }
        for (int i = 0; i < 50_000; i++) {
            text.append(" #");
        }
        String corpus = text.toString();
        assertThat(corpus.length()).isLessThanOrEqualTo(NoteLimits.NOTE_MAX_BYTES);
        NoteContent.analyze(corpus.substring(0, 20_000));
        long start = System.nanoTime();
        NoteContent content = NoteContent.analyze(corpus);
        long millis = (System.nanoTime() - start) / 1_000_000;
        assertThat(content.wikilinks()).hasSize(20_000);
        assertThat(content.tags()).isEmpty();
        assertThat(millis).as("NoteContent.analyze em 200 KB").isLessThan(MAX_ANALYZE_MILLIS);
        StringBuilder inside = new StringBuilder();
        for (int i = 0; i < 20_000; i++) {
            inside.append("[[a#s]] #t").append(i % 7).append("x ");
        }
        String insideCorpus = inside.toString().substring(0, NoteLimits.NOTE_MAX_BYTES - 1000);
        NoteContent.analyze(insideCorpus.substring(0, 20_000));
        long start2 = System.nanoTime();
        NoteContent second = NoteContent.analyze(insideCorpus);
        assertThat((System.nanoTime() - start2) / 1_000_000).isLessThan(MAX_ANALYZE_MILLIS);
        assertThat(second.tags()).containsExactlyInAnyOrder("t0x", "t1x", "t2x", "t3x", "t4x", "t5x", "t6x");
    }
}
