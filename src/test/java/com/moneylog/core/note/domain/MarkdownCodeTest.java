package com.moneylog.core.note.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("MarkdownCode: onde há código (links e tags dentro dele não valem)")
class MarkdownCodeTest {

    private static String covered(String text) {
        List<int[]> ranges = MarkdownCode.ranges(text, 0);
        StringBuilder out = new StringBuilder();
        for (int[] r : ranges) {
            out.append('[').append(text, r[0], r[1]).append(']');
        }
        return out.toString();
    }

    @Test
    @DisplayName("trecho entre crases e crases duplas")
    void deveAcharCodeInline() {
        assertThat(covered("a `x` b ``y`z`` c")).isEqualTo("[`x`][``y`z``]");
        assertThat(covered("sem codigo")).isEmpty();
    }

    @Test
    @DisplayName("bloco cercado por ``` ou ~~~, até o fechamento ou o fim do texto")
    void deveAcharBlocoCercado() {
        assertThat(covered("a\n```js\n[[x]]\n```\nb")).isEqualTo("[```js\n[[x]]\n```\n]");
        assertThat(covered("a\n~~~\n[[x]]\n~~~\nb")).isEqualTo("[~~~\n[[x]]\n~~~\n]");
        assertThat(covered("a\n```\n[[sem fechar]]")).isEqualTo("[```\n[[sem fechar]]]");
        assertThat(covered("```\ncrase ``` no meio nao fecha\n```\nfim")).isEqualTo("[```\ncrase ``` no meio nao fecha\n```\n]");
    }

    @Test
    @DisplayName("```[[x]]``` na mesma linha é código inline, não bloco")
    void deveTratarTresCrasesNaMesmaLinha() {
        assertThat(covered("veja ```[[bloco]]``` fim")).isEqualTo("[```[[bloco]]```]");
        assertThat(covered("```inline```\nresto")).isEqualTo("[```inline```]");
    }

    @Test
    @DisplayName("crase sem par fica literal; o trecho não atravessa linha em branco; \\` escapa")
    void deveIgnorarCrasesSemPar() {
        assertThat(covered("uma ` solta")).isEmpty();
        assertThat(covered("abre ` aqui\n\ne fecha ` ali")).isEmpty();
        assertThat(covered("abre ` aqui\ne fecha ` ali")).isEqualTo("[` aqui\ne fecha `]");
        assertThat(covered("escapada \\` nao abre ` nem fecha")).isEmpty();
    }

    @Test
    @DisplayName("fechamento exige o mesmo marcador e comprimento mínimo; recuo de 4 espaços não abre cerca (é bloco indentado)")
    void deveExigirMesmoMarcador() {
        assertThat(covered("```\nx\n~~~\ny\n```\nz")).isEqualTo("[```\nx\n~~~\ny\n```\n]");
        assertThat(covered("````\nx\n```\ny\n````\nz")).isEqualTo("[````\nx\n```\ny\n````\n]");
        assertThat(covered("    ```\nx")).isEqualTo("[    ```\n]");
        assertThat(covered("   ```\nx\n   ```\n")).isEqualTo("[   ```\nx\n   ```\n]");
    }

    @Test
    @DisplayName("covers e rangeAt usam busca binária; from ignora o frontmatter")
    void deveConsultarIntervalos() {
        String text = "x `a` y `b` z";
        List<int[]> ranges = MarkdownCode.ranges(text, 0);
        assertThat(MarkdownCode.covers(ranges, 3)).isTrue();
        assertThat(MarkdownCode.covers(ranges, 0)).isFalse();
        assertThat(MarkdownCode.covers(ranges, 9)).isTrue();
        assertThat(MarkdownCode.covers(ranges, 12)).isFalse();
        assertThat(MarkdownCode.rangeAt(ranges, 99)).isNull();
        assertThat(MarkdownCode.ranges("`a` `b`", 4)).hasSize(1);
    }

    @Test
    @DisplayName("M5: bloco indentado (4 espaços ou tab) depois de linha em branco é código; parágrafo e lista não")
    void deveAcharBlocoIndentado() {
        assertThat(covered("texto\n\n    [[A]] em código\n    outra linha\n\nfim")).isEqualTo("[    [[A]] em código\n    outra linha\n]");
        assertThat(covered("texto\n\n\t[[A]] com tab\nfim")).isEqualTo("[\t[[A]] com tab\n]");
        assertThat(covered("    [[A]] no começo do texto")).isEqualTo("[    [[A]] no começo do texto]");
        // continuação de parágrafo (sem linha em branco antes) não é código
        assertThat(covered("texto\n    [[A]] continua o parágrafo")).isEmpty();
        // item de lista aninhado e continuação de item não são código
        assertThat(covered("- item\n    - [[A]] aninhado\n\n    continuação [[B]]\n")).isEmpty();
        assertThat(covered("1. item\n\n       [[A]] dentro do item\n")).isEmpty();
        // depois que a lista acaba, o recuo volta a ser código
        assertThat(covered("- item\n\nparágrafo\n\n    [[A]] código\n")).isEqualTo("[    [[A]] código\n]");
        // linha em branco entre duas linhas indentadas continua o mesmo bloco; crase dentro do bloco não abre trecho
        assertThat(covered("x\n\n    `a\n\n    b`\n")).isEqualTo("[    `a\n\n    b`\n]");
        // o bloco acaba na primeira linha sem recuo
        assertThat(covered("x\n\n    a\n\ny [[B]]\n")).isEqualTo("[    a\n]");
    }

    @Test
    @DisplayName("M5: bloco indentado não é lido como wikilink nem como tag, e o texto fora dele continua valendo")
    void blocoIndentadoNaoViraLink() {
        String text = "veja [[Real]] #real\n\n    [[Codigo]] #codigo\n\ndepois [[Outro]]";
        assertThat(WikilinkParser.parse(text).stream().map(Wikilink::target)).containsExactly("Real", "Outro");
        assertThat(NoteTags.extract(text, 0, List.of(), WikilinkParser.parse(text))).containsExactly("real");
        assertThat(WikilinkRewriter.rewrite(text, 0, l -> "Z")).isEqualTo("veja [[Z]] #real\n\n    [[Codigo]] #codigo\n\ndepois [[Z]]");
    }
}
