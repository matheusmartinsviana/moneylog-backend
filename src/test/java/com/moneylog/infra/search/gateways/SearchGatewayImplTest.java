package com.moneylog.infra.search.gateways;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Busca global: texto puro do snippet e escape dos curingas (S-287)")
class SearchGatewayImplTest {

    @Test
    @DisplayName("snippet: controles (inclusive ESC e C1) viram espaço, espaços e quebras colapsam, até 160 caracteres; nulo vira vazio")
    void deveGerarTextoPuro() {
        assertThat(SearchGatewayImpl.snippet("linha 1\nlinha\t2\u001Bcom\u0085controle   e    espaços")).isEqualTo("linha 1 linha 2 com controle e espaços");
        assertThat(SearchGatewayImpl.snippet("x".repeat(400))).hasSize(160);
        assertThat(SearchGatewayImpl.snippet(null)).isEmpty();
        assertThat(SearchGatewayImpl.snippet("<script>alert(1)</script> **negrito**")).as("o texto não é interpretado nem escapado: o front o exibe como texto")
                .isEqualTo("<script>alert(1)</script> **negrito**");
    }

    @Test
    @DisplayName("escapeLike: %, _ e \\ viram texto literal")
    void deveEscaparOsCuringas() {
        assertThat(SearchGatewayImpl.escapeLike("100%_a\\b")).isEqualTo("100\\%\\_a\\\\b");
        assertThat(SearchGatewayImpl.escapeLike("sem curinga")).isEqualTo("sem curinga");
    }
}
