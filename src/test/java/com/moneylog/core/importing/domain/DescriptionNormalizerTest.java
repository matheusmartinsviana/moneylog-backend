package com.moneylog.core.importing.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("DescriptionNormalizer")
class DescriptionNormalizerTest {

    @Test
    @DisplayName("\"  Ifood *XYZ  - São Paulo \" vira \"IFOOD XYZ SAO PAULO\"")
    void deveNormalizarOExemploDoD1() {
        assertThat(DescriptionNormalizer.normalize("  Ifood *XYZ  - São Paulo ")).isEqualTo("IFOOD XYZ SAO PAULO");
    }

    @ParameterizedTest(name = "\"{0}\" → \"{1}\"")
    @CsvSource(delimiter = '|', value = {
            "Pão de Açúcar 123|PAO DE ACUCAR 123",
            "PIX  enviado/Maria.José|PIX ENVIADO MARIA JOSE",
            "Uber*Trip 22/10|UBER TRIP 22 10",
            "ÇÃÕÉÍÓÚÜ àèìòù|CAOEIOUU AEIOU",
            "  ---  |''",
            "loja 01 #2|LOJA 01 2"})
    @DisplayName("maiúsculas, sem acento, pontuação vira espaço, espaços colapsados, dígitos mantidos")
    void deveNormalizar(String original, String esperado) {
        assertThat(DescriptionNormalizer.normalize(original)).isEqualTo(esperado.equals("''") ? "" : esperado);
    }

    @Test
    @DisplayName("tabulação e quebra de linha viram espaço")
    void deveTratarEspacosEspeciais() {
        assertThat(DescriptionNormalizer.normalize("café\tcom\nleite")).isEqualTo("CAFE COM LEITE");
    }

    @Test
    @DisplayName("null vira texto vazio e o resultado é idempotente")
    void deveSerIdempotente() {
        assertThat(DescriptionNormalizer.normalize(null)).isEmpty();
        String uma = DescriptionNormalizer.normalize("Ifood *XYZ - São Paulo");
        assertThat(DescriptionNormalizer.normalize(uma)).isEqualTo(uma);
    }
}
