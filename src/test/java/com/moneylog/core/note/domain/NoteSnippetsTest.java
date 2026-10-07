package com.moneylog.core.note.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("NoteSnippets: trechos de texto puro de até 160 caracteres")
class NoteSnippetsTest {

    @Test
    @DisplayName("contexto curto vem inteiro, com quebras de linha colapsadas")
    void deveColapsar() {
        String text = "linha um\n\nveja [[MacBook]]   hoje\nfim";
        assertThat(NoteSnippets.around(text, text.indexOf("[["), text.indexOf("]]") + 2, 160)).isEqualTo("linha um veja [[MacBook]] hoje fim");
    }

    @Test
    @DisplayName("CA4 (T109): contexto longo é cortado em até 160 caracteres, contém o link e marca as pontas com reticências")
    void deveCortarEm160() {
        String text = "a ".repeat(300) + "[[MacBook]]" + " b".repeat(300);
        int start = text.indexOf("[[");
        String snippet = NoteSnippets.around(text, start, start + 11, 160);
        assertThat(snippet.length()).isLessThanOrEqualTo(160);
        assertThat(snippet).contains("[[MacBook]]").startsWith("…").endsWith("…");
    }

    @Test
    @DisplayName("link no começo ou no fim do texto: só uma ponta com reticências; link maior que o limite é cortado")
    void deveTratarAsPontas() {
        String start = "[[MacBook]]" + " x".repeat(200);
        assertThat(NoteSnippets.around(start, 0, 11, 160)).startsWith("[[MacBook]]").endsWith("…").hasSizeLessThanOrEqualTo(160);
        String end = "x ".repeat(200) + "[[MacBook]]";
        assertThat(NoteSnippets.around(end, end.length() - 11, end.length(), 160)).startsWith("…").endsWith("[[MacBook]]");
        String huge = "[[" + "y".repeat(300) + "]]";
        assertThat(NoteSnippets.around(huge, 0, huge.length(), 160)).hasSize(160);
    }

    @Test
    @DisplayName("não parte um par substituto (emoji) ao cortar")
    void deveRespeitarPares() {
        String text = "🙂".repeat(200) + "[[x]]" + "🙂".repeat(200);
        int start = text.indexOf("[[");
        String snippet = NoteSnippets.around(text, start, start + 5, 160);
        assertThat(snippet.length()).isLessThanOrEqualTo(160);
        for (int i = 0; i < snippet.length(); i++) {
            char c = snippet.charAt(i);
            if (Character.isHighSurrogate(c)) {
                assertThat(i + 1 < snippet.length() && Character.isLowSurrogate(snippet.charAt(i + 1))).isTrue();
            }
        }
    }

    @Test
    @DisplayName("head: início do texto colapsado e cortado; não altera HTML (texto puro, nunca renderizado)")
    void deveCortarOInicio() {
        assertThat(NoteSnippets.head("  <script>alert(1)</script>\n\n  fim ", 160)).isEqualTo("<script>alert(1)</script> fim");
        assertThat(NoteSnippets.head("x".repeat(500), 160)).hasSize(160);
        assertThat(NoteSnippets.head("🙂".repeat(200), 161).length()).isLessThanOrEqualTo(161);
    }
}
