package com.moneylog.core.importing.parser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.moneylog.core.importing.domain.ImportDirection;
import com.moneylog.core.importing.exception.ImportingExceptions.ImportFormatUnsupportedException;
import com.moneylog.core.importing.exception.ImportingExceptions.ImportParseFailedException;

@DisplayName("OfxStatementParser (T123)")
class OfxStatementParserTest {

    private static final String SGML = """
            OFXHEADER:100
            DATA:OFXSGML
            VERSION:102
            ENCODING:USASCII
            CHARSET:1252

            <OFX>
            <BANKMSGSRSV1><STMTTRNRS><STMTRS>
            <CURDEF>BRL
            <BANKACCTFROM><BANKID>341<ACCTID>12345-6<ACCTTYPE>CHECKING</BANKACCTFROM>
            <BANKTRANLIST>
            <STMTTRN>
            <TRNTYPE>DEBIT
            <DTPOSTED>20261005120000[-3:BRT]
            <TRNAMT>-82.90
            <FITID>2026100501
            <NAME>COMPRA
            <MEMO>IFOOD RESTAURANTE
            </STMTTRN>
            <STMTTRN>
            <TRNTYPE>CREDIT
            <DTPOSTED>20261006
            <TRNAMT>5000.00
            <FITID>2026100602
            <NAME>SALARIO
            <MEMO>CREDITO SALARIO
            </STMTTRN>
            <STMTTRN>
            <DTPOSTED>20261007
            <TRNAMT>0.00
            <FITID>3
            <MEMO>Zero
            </STMTTRN>
            </BANKTRANLIST>
            </STMTRS></STMTTRNRS></BANKMSGSRSV1>
            </OFX>
            """;

    private static final String XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <?OFX OFXHEADER="200" VERSION="211" SECURITY="NONE" OLDFILEUID="NONE" NEWFILEUID="NONE"?>
            <OFX><CREDITCARDMSGSRSV1><CCSTMTTRNRS><CCSTMTRS><CURDEF>BRL</CURDEF>
            <CCACCTFROM><ACCTID>0000</ACCTID></CCACCTFROM>
            <BANKTRANLIST>
            <STMTTRN><TRNTYPE>DEBIT</TRNTYPE><DTPOSTED>20261010000000</DTPOSTED><TRNAMT>-120,50</TRNAMT><FITID>A1</FITID><MEMO>Mercado &amp; Cia</MEMO></STMTTRN>
            </BANKTRANLIST>
            </CCSTMTRS></CCSTMTTRNRS></CREDITCARDMSGSRSV1></OFX>
            """;

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.ISO_8859_1);
    }

    @Test
    @DisplayName("SGML 1.x: DTPOSTED com e sem fuso, TRNAMT com sinal, FITID, MEMO quando NAME é genérico; valor zero ignorado e listado")
    void sgml() {
        ParsedStatement parsed = OfxStatementParser.parse(bytes(SGML));
        assertThat(parsed.cardStatement()).isFalse();
        assertThat(parsed.rows()).hasSize(2);
        assertThat(parsed.rows().get(0).date()).isEqualTo(LocalDate.of(2026, 10, 5));
        assertThat(parsed.rows().get(0).amount().cents()).isEqualTo(8290);
        assertThat(parsed.rows().get(0).direction()).isEqualTo(ImportDirection.OUT);
        assertThat(parsed.rows().get(0).fitId()).isEqualTo("2026100501");
        assertThat(parsed.rows().get(0).description()).isEqualTo("IFOOD RESTAURANTE");
        assertThat(parsed.rows().get(1).date()).isEqualTo(LocalDate.of(2026, 10, 6));
        assertThat(parsed.rows().get(1).direction()).isEqualTo(ImportDirection.IN);
        assertThat(parsed.skipped()).extracting(ParsedStatement.Skipped::reason).containsExactly("valor zero");
    }

    @Test
    @DisplayName("XML 2.x de cartão: reconhecido como CARD, entidade &amp; decodificada, vírgula decimal, ACCTID nunca guardado")
    void xmlCartao() {
        ParsedStatement parsed = OfxStatementParser.parse(XML.getBytes(StandardCharsets.UTF_8));
        assertThat(parsed.cardStatement()).isTrue();
        assertThat(parsed.rows()).hasSize(1);
        assertThat(parsed.rows().get(0).description()).isEqualTo("Mercado & Cia");
        assertThat(parsed.rows().get(0).amount().cents()).isEqualTo(12050);
        assertThat(parsed.toString()).doesNotContain("0000");
    }

    @Test
    @DisplayName("XXE: DOCTYPE/ENTITY é recusado sem resolver nada")
    void xxe() {
        String xxe = "<?xml version=\"1.0\"?><!DOCTYPE foo [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]><OFX><STMTTRN><DTPOSTED>20261010</DTPOSTED>"
                + "<TRNAMT>-1.00</TRNAMT><MEMO>&xxe;</MEMO></STMTTRN></OFX>";
        assertThatThrownBy(() -> OfxStatementParser.parse(xxe.getBytes(StandardCharsets.UTF_8))).isInstanceOf(ImportParseFailedException.class)
                .hasMessageContaining("DOCTYPE");
    }

    @Test
    @DisplayName("arquivo truncado, vazio ou que não é OFX: IMPORT_PARSE_FAILED informativo")
    void truncado() {
        String truncated = SGML.substring(0, SGML.indexOf("<TRNAMT>-82.90"));
        assertThatThrownBy(() -> OfxStatementParser.parse(bytes(truncated))).isInstanceOf(ImportParseFailedException.class).hasMessageContaining("truncado");
        assertThatThrownBy(() -> OfxStatementParser.parse(new byte[0])).isInstanceOf(ImportParseFailedException.class);
        assertThatThrownBy(() -> OfxStatementParser.parse(bytes("Data,Valor\n1,2"))).isInstanceOf(ImportParseFailedException.class);
    }

    @Test
    @DisplayName("moeda diferente de BRL é recusada com a moeda no texto (S-2)")
    void moeda() {
        String usd = SGML.replace("<CURDEF>BRL", "<CURDEF>USD");
        assertThatThrownBy(() -> OfxStatementParser.parse(bytes(usd))).isInstanceOf(ImportFormatUnsupportedException.class).hasMessageContaining("USD");
    }

    @Test
    @DisplayName("data ou valor inválido: a linha é ignorada e listada, as outras seguem")
    void linhasRuins() {
        String ofx = SGML.replace("<DTPOSTED>20261006", "<DTPOSTED>2026-ab").replace("<TRNAMT>-82.90", "<TRNAMT>abc");
        ParsedStatement parsed = OfxStatementParser.parse(bytes(ofx));
        assertThat(parsed.rows()).isEmpty();
        assertThat(parsed.skipped()).extracting(ParsedStatement.Skipped::reason).contains("data inválida (DTPOSTED)", "valor inválido (TRNAMT)");
    }

    @Test
    @DisplayName("sem FITID a linha vem sem identificador (a chave usa a descrição)")
    void semFitId() {
        ParsedStatement parsed = OfxStatementParser.parse(bytes(SGML.replace("<FITID>2026100501\n", "")));
        assertThat(parsed.rows().get(0).fitId()).isNull();
    }

    @Test
    @DisplayName("M1: FITID acima de 100 caracteres ignora a linha com motivo (não estoura o banco)")
    void fitIdLongo() {
        String ofx = "<OFX><BANKMSGSRSV1><STMTTRNRS><STMTRS><CURDEF>BRL<BANKTRANLIST>"
                + "<STMTTRN><DTPOSTED>20261010<TRNAMT>-10.00<FITID>" + "X".repeat(101) + "<MEMO>Padaria</STMTTRN>"
                + "<STMTTRN><DTPOSTED>20261010<TRNAMT>-5.00<FITID>" + "Y".repeat(100) + "<MEMO>Cafe</STMTTRN>"
                + "</BANKTRANLIST></STMTRS></STMTTRNRS></BANKMSGSRSV1></OFX>";
        ParsedStatement parsed = OfxStatementParser.parse(bytes(ofx));
        assertThat(parsed.rows()).hasSize(1);
        assertThat(parsed.skipped()).extracting(ParsedStatement.Skipped::reason).singleElement().asString().contains("FITID");
    }
}
