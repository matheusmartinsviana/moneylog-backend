package com.moneylog.core.ai.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RedactorTest {

    private final Redactor redactor = new Redactor(List.of("João Silva", "Maria Souza"));

    @Test
    @DisplayName("PIX: o favorecido não vai ao provedor, só o tipo da operação")
    void devePermanecerSoOTipoDaOperacaoEmPixTedDoc() {
        assertThat(redactor.redact("PIX JOAO SILVA CPF 123.456.789-09 joao@x.com").text()).isEqualTo("[PIX]");
        assertThat(redactor.redact("Pix enviado - Fulano de Tal").text()).isEqualTo("[PIX]");
        assertThat(redactor.redact("TED recebida EMPRESA XYZ LTDA").text()).isEqualTo("[TED]");
        assertThat(redactor.redact("DOC 0001 Beltrano").text()).isEqualTo("[DOC]");
        assertThat(redactor.redact("PIX JOAO").types()).contains("PAYEE");
    }

    @Test
    @DisplayName("sem PIX: CPF, telefone, e-mail, cartão e nome de pessoa cadastrada são mascarados")
    void deveMascararDadosPessoais() {
        Redactor.Result r = redactor.redact("COMPRA Joao Silva CPF 123.456.789-09 TEL (11) 91234-5678 joao@x.com 5555 4444 3333 2222");
        assertThat(r.text()).isEqualTo("COMPRA PESSOA A CPF [CPF] TEL [TEL] [EMAIL] [CARTAO]");
        assertThat(r.types()).containsExactlyInAnyOrder("CPF", "PHONE", "EMAIL", "CARD", "PERSON");
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "MERCADO 12.345.678/0001-90, MERCADO [CNPJ]",
            "BOLETO 123456, BOLETO [NUM]",
            "LOJA 12345, LOJA 12345",
            "PAGTO Maria Souza, PAGTO PESSOA B",
            "cafe da manha, CAFE DA MANHA"
    })
    void deveRedigirCasosComuns(String input, String expected) {
        assertThat(redactor.redact(input).text()).isEqualTo(expected);
    }

    @Test
    @DisplayName("fuzz de 2.000 descrições: nunca sobra CPF, e-mail, sequência longa de dígitos nem nome cadastrado")
    void naoDeveVazarNadaNoFuzz() {
        Random random = new Random(42);
        String[] words = {"COMPRA", "LOJA", "PAGTO", "JOAO SILVA", "Maria Souza", "TEL", "CPF", "CNPJ", "mercado", "UBER"};
        for (int i = 0; i < 2000; i++) {
            StringBuilder sb = new StringBuilder();
            for (int w = 0; w < 1 + random.nextInt(5); w++) {
                switch (random.nextInt(6)) {
                    case 0 -> sb.append(String.format("%03d.%03d.%03d-%02d", random.nextInt(1000), random.nextInt(1000), random.nextInt(1000), random.nextInt(100)));
                    case 1 -> sb.append("user").append(random.nextInt(99)).append("@mail.com");
                    case 2 -> sb.append(String.format("%04d %04d %04d %04d", random.nextInt(10000), random.nextInt(10000), random.nextInt(10000), random.nextInt(10000)));
                    case 3 -> sb.append("(").append(10 + random.nextInt(89)).append(") 9").append(String.format("%04d-%04d", random.nextInt(10000), random.nextInt(10000)));
                    default -> sb.append(words[random.nextInt(words.length)]);
                }
                sb.append(' ');
            }
            String out = redactor.redact(sb.toString()).text();
            assertThat(out).doesNotContainPattern("\\d{6,}").doesNotContainPattern("\\d{3}\\.\\d{3}\\.\\d{3}-\\d{2}")
                    .doesNotContainPattern("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}").doesNotContain("JOAO SILVA").doesNotContain("MARIA SOUZA");
        }
    }

    @Test
    void deveSerIdempotente() {
        String once = redactor.redact("COMPRA Joao Silva 5555 4444 3333 2222").text();
        assertThat(redactor.redact(once).text()).isEqualTo(once);
    }
}
