package com.moneylog.core.importing.parser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.moneylog.core.importing.domain.ImportDirection;
import com.moneylog.core.importing.exception.ImportingExceptions.ImportMappingInvalidException;
import com.moneylog.core.importing.exception.ImportingExceptions.ImportParseFailedException;
import com.moneylog.core.importing.exception.ImportingExceptions.ImportTooLargeException;
import com.moneylog.core.importing.parser.ParsedStatement.Row;

@DisplayName("CsvStatementParser (T122)")
class CsvStatementParserTest {

    private static final String NUBANK = """
            Data,Valor,Identificador,Descrição
            05/10/2026,-82.90,abc,Ifood *Restaurante
            06/10/2026,5000.00,def,Salário Outubro
            07/10/2026,0.00,ghi,Tarifa zerada
            """;

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("UTF-8 com vírgula: detecta cabeçalho, mapeamento (data, descrição, valor), sinal e ignora valor zero listando o motivo")
    void nubank() {
        ParsedStatement parsed = CsvStatementParser.parse(utf8(NUBANK), null);
        assertThat(parsed.rows()).extracting(Row::date, r -> r.amount().cents(), Row::direction, Row::description).containsExactly(
                org.assertj.core.groups.Tuple.tuple(LocalDate.of(2026, 10, 5), 8290L, ImportDirection.OUT, "Ifood *Restaurante"),
                org.assertj.core.groups.Tuple.tuple(LocalDate.of(2026, 10, 6), 500000L, ImportDirection.IN, "Salário Outubro"));
        assertThat(parsed.skipped()).hasSize(1);
        assertThat(parsed.skipped().get(0).reason()).isEqualTo("valor zero");
        assertThat(parsed.skipped().get(0).lineNumber()).isEqualTo(4);
    }

    @Test
    @DisplayName("ISO-8859-1, ponto e vírgula e 1.234,56: acentos preservados e centavos exatos")
    void latin1() {
        String text = "Data;Histórico;Valor\n15/10/2026;Pagamento ação;-1.234,56\n16/10/2026;Crédito;2.000,00\n";
        ParsedStatement parsed = CsvStatementParser.parse(text.getBytes(StandardCharsets.ISO_8859_1), null);
        assertThat(parsed.rows()).hasSize(2);
        assertThat(parsed.rows().get(0).description()).isEqualTo("Pagamento ação");
        assertThat(parsed.rows().get(0).amount().cents()).isEqualTo(123456);
        assertThat(parsed.rows().get(0).direction()).isEqualTo(ImportDirection.OUT);
        assertThat(parsed.rows().get(1).amount().cents()).isEqualTo(200000);
        assertThat(CsvStatementParser.detect(text.getBytes(StandardCharsets.ISO_8859_1)).encoding()).isEqualTo("ISO-8859-1");
        assertThat(CsvStatementParser.detect(text.getBytes(StandardCharsets.ISO_8859_1)).delimiter()).isEqualTo(';');
    }

    @Test
    @DisplayName("BOM UTF-8 é tratado")
    void bom() {
        byte[] body = utf8(NUBANK);
        byte[] withBom = new byte[body.length + 3];
        withBom[0] = (byte) 0xEF;
        withBom[1] = (byte) 0xBB;
        withBom[2] = (byte) 0xBF;
        System.arraycopy(body, 0, withBom, 3, body.length);
        assertThat(CsvStatementParser.parse(withBom, null).rows()).hasSize(2);
    }

    @Test
    @DisplayName("colunas de débito e crédito: débito sai, crédito entra")
    void debitoCredito() {
        String text = "Data;Descrição;Débito;Crédito\n01/10/2026;Mercado;120,00;\n02/10/2026;Depósito;;300,00\n";
        ParsedStatement parsed = CsvStatementParser.parse(utf8(text), null);
        assertThat(parsed.rows()).extracting(Row::direction).containsExactly(ImportDirection.OUT, ImportDirection.IN);
        assertThat(parsed.rows()).extracting(r -> r.amount().cents()).containsExactly(12000L, 30000L);
    }

    @Test
    @DisplayName("invert (cartão traz compra como positivo) troca o sentido")
    void invert() {
        String text = "Data,Descrição,Valor\n01/10/2026,Compra,50.00\n";
        ColumnMapping mapping = new ColumnMapping(0, 1, 2, null, null, true, true, null);
        assertThat(CsvStatementParser.parse(utf8(text), mapping).rows().get(0).direction()).isEqualTo(ImportDirection.OUT);
    }

    @Test
    @DisplayName("rodapé, totais, linhas em branco, data inválida (31/02) e valor ilegível: ignorados e listados, sem derrubar o arquivo")
    void ignoradas() {
        String text = """
                Data,Descrição,Valor
                01/10/2026,Compra A,-10.00

                31/02/2026,Data impossível,-5.00
                02/10/2026,Valor ruim,abc
                ,Saldo final,1.000,00
                """;
        ParsedStatement parsed = CsvStatementParser.parse(utf8(text), new ColumnMapping(0, 1, 2, null, null, false, true, ','));
        assertThat(parsed.rows()).hasSize(1);
        assertThat(parsed.skipped()).extracting(ParsedStatement.Skipped::reason)
                .contains("data inválida", "valor inválido", "linha de total ou saldo");
    }

    @Test
    @DisplayName("aspas com vírgula e quebra de linha dentro do campo; aspas dobradas")
    void aspas() {
        String text = "Data,Descrição,Valor\n01/10/2026,\"Loja \"\"X\"\", filial\nCentro\",-10.00\n";
        ParsedStatement parsed = CsvStatementParser.parse(utf8(text), null);
        assertThat(parsed.rows().get(0).description()).isEqualTo("Loja \"X\", filial\nCentro");
    }

    @Test
    @DisplayName("fórmula na descrição é só texto (nada é executado nem alterado)")
    void formula() {
        String text = "Data,Descrição,Valor\n01/10/2026,=HYPERLINK(\"http://x\"),-10.00\n";
        assertThat(CsvStatementParser.parse(utf8(text), null).rows().get(0).description()).isEqualTo("=HYPERLINK(\"http://x\")");
    }

    @Test
    @DisplayName("sem cabeçalho: detecta data, valor e descrição pela primeira linha")
    void semCabecalho() {
        String text = "01/10/2026;Padaria;-12,50\n02/10/2026;Salário;3.000,00\n";
        ParsedStatement parsed = CsvStatementParser.parse(utf8(text), null);
        assertThat(parsed.rows()).hasSize(2);
        assertThat(parsed.rows().get(0).description()).isEqualTo("Padaria");
    }

    @Test
    @DisplayName("limites: mais de 5.000 linhas e mais de 5 MB são IMPORT_TOO_LARGE; vazio e aspas abertas são IMPORT_PARSE_FAILED; sem colunas é mapeamento inválido")
    void limites() {
        StringBuilder many = new StringBuilder("Data,Descrição,Valor\n");
        for (int i = 0; i < 5001; i++) {
            many.append("01/10/2026,Linha ").append(i).append(",-1.00\n");
        }
        assertThatThrownBy(() -> CsvStatementParser.parse(utf8(many.toString()), null)).isInstanceOf(ImportTooLargeException.class);
        assertThatThrownBy(() -> CsvStatementParser.parse(new byte[5 * 1024 * 1024 + 1], null)).isInstanceOf(ImportTooLargeException.class);
        assertThatThrownBy(() -> CsvStatementParser.parse(new byte[0], null)).isInstanceOf(ImportParseFailedException.class);
        assertThatThrownBy(() -> CsvStatementParser.parse(utf8("Data,Descrição,Valor\n01/10/2026,\"aberta,-1.00\n"), null))
                .isInstanceOf(ImportParseFailedException.class).hasMessageContaining("aspas");
        assertThatThrownBy(() -> CsvStatementParser.parse(utf8("foo,bar\nbaz,qux\n"), null)).isInstanceOf(ImportMappingInvalidException.class);
    }

    @Test
    @DisplayName("propriedade: o que o parser lê de um CSV gerado volta exatamente igual (data, centavos, direção, descrição)")
    void roundTrip() {
        java.util.Random random = new java.util.Random(11);
        StringBuilder csv = new StringBuilder("Data;Descrição;Valor\n");
        java.util.List<long[]> expected = new java.util.ArrayList<>();
        for (int i = 0; i < 500; i++) {
            long cents = (random.nextInt(2_000_000) + 1) * (random.nextBoolean() ? 1 : -1);
            LocalDate date = LocalDate.of(2026, 1, 1).plusDays(random.nextInt(300));
            csv.append(String.format(java.util.Locale.of("pt", "BR"), "%02d/%02d/%d;Linha %d;%,.2f%n", date.getDayOfMonth(), date.getMonthValue(),
                    date.getYear(), i, cents / 100.0 == 0 ? 0.01 : java.math.BigDecimal.valueOf(cents, 2)));
            expected.add(new long[] {date.toEpochDay(), cents});
        }
        ParsedStatement parsed = CsvStatementParser.parse(utf8(csv.toString()), null);
        assertThat(parsed.rows()).hasSize(500);
        for (int i = 0; i < 500; i++) {
            Row row = parsed.rows().get(i);
            long signed = row.direction() == ImportDirection.IN ? row.amount().cents() : -row.amount().cents();
            assertThat(row.date().toEpochDay()).isEqualTo(expected.get(i)[0]);
            assertThat(signed).as("linha %d", i).isEqualTo(expected.get(i)[1]);
            assertThat(row.description()).isEqualTo("Linha " + i);
        }
    }
}
