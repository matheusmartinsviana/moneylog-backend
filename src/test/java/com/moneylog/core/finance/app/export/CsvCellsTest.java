package com.moneylog.core.finance.app.export;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("Células do CSV: escape e proteção contra injeção de fórmula (T97 CA2, S-153)")
class CsvCellsTest {

    @ParameterizedTest
    @ValueSource(strings = {"=1+1", "+55 11", "-5% desconto", "@SOMA(A1)", "\tcmd", "\rcmd"})
    @DisplayName("texto que começa com =, +, -, @, tabulação ou retorno de carro recebe um ' na frente")
    void deveNeutralizarFormula(String text) {
        String cell = CsvCells.text(text);
        String unquoted = cell.startsWith("\"") ? cell.substring(1, cell.length() - 1) : cell;
        assertThat(unquoted).isEqualTo("'" + text);
    }

    @Test
    @DisplayName("=cmd|' /C calc'!A0 vira '=cmd|' /C calc'!A0 e continua sendo texto")
    void deveNeutralizarOExemploClassico() {
        assertThat(CsvCells.text("=cmd|' /C calc'!A0")).isEqualTo("'=cmd|' /C calc'!A0");
    }

    @Test
    @DisplayName("ponto e vírgula, aspas e quebra de linha entre aspas, com as aspas dobradas")
    void deveEscaparSeparadorAspasEQuebras() {
        assertThat(CsvCells.text("a;b")).isEqualTo("\"a;b\"");
        assertThat(CsvCells.text("diz \"oi\"")).isEqualTo("\"diz \"\"oi\"\"\"");
        assertThat(CsvCells.text("linha1\nlinha2")).isEqualTo("\"linha1\nlinha2\"");
        assertThat(CsvCells.text("linha1\r\nlinha2")).isEqualTo("\"linha1\r\nlinha2\"");
        assertThat(CsvCells.text("=a;b")).as("neutraliza e depois escapa").isEqualTo("\"'=a;b\"");
    }

    @Test
    @DisplayName("texto comum fica como está; nulo e vazio viram célula vazia; sinal no meio não é fórmula")
    void deveManterOTextoComum() {
        assertThat(CsvCells.text("Mercado")).isEqualTo("Mercado");
        assertThat(CsvCells.text("a-b = c")).isEqualTo("a-b = c");
        assertThat(CsvCells.text("Açaí")).isEqualTo("Açaí");
        assertThat(CsvCells.text(null)).isEmpty();
        assertThat(CsvCells.text("")).isEmpty();
    }

    @Test
    @DisplayName("valor com vírgula decimal e sem milhar")
    void deveFormatarOValor() {
        assertThat(CsvCells.amount("1234.50")).isEqualTo("1234,50");
        assertThat(CsvCells.amount("0.05")).isEqualTo("0,05");
        assertThat(CsvCells.amount("1200.00")).isEqualTo("1200,00");
    }
}
