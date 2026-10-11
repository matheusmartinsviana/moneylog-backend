package com.moneylog.infra.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.moneylog.util.ContractValidation;

@DisplayName("Contrato: tabela de códigos de erro × constantes do código")
class ContractErrorCodesTest {

    private static final Path MAIN = Path.of("src/main/java");
    private static final Pattern ROW = Pattern.compile("^\\| (404|409|422) \\|(.*)\\|$", Pattern.MULTILINE);
    private static final Pattern CODE = Pattern.compile("`([A-Z][A-Z0-9_]+)`");

    private static final Set<String> INTERNAL = Set.of("INVOICE_CHAIN_INCONSISTENT", "CARD_INVALID_INSTALLMENT");

    private static final Set<String> NOT_CODES = Set.of("CARD_PURCHASE", "CARD_COMMITTED", "CARD_LIMIT_USAGE", "CARD_CLOSING_SOON");

    @Test
    @DisplayName("todo code 404/409/422 da tabela do contrato existe no código")
    void deveExistirNoCodigoTodoCodeDaTabela() throws IOException {
        String source = allMainSources();
        Set<String> missing = new TreeSet<>();
        for (String code : contractCodes()) {
            if (!source.contains("\"" + code + "\"")) {
                missing.add(code);
            }
        }
        assertThat(missing).as("codes do contrato sem constante no código").isEmpty();
    }

    @Test
    @DisplayName("os codes de cartão e fatura do domínio aparecem na tabela do contrato (ou são invariantes internas)")
    void deveListarNoContratoOsCodesDeCartao() throws IOException {
        Set<String> inContract = contractCodes();
        Set<String> declared = new TreeSet<>();
        Pattern literal = Pattern.compile("\"((?:CARD|INVOICE)_[A-Z0-9_]+)\"");
        for (Path file : javaFiles()) {
            Matcher matcher = literal.matcher(Files.readString(file));
            while (matcher.find()) {
                declared.add(matcher.group(1));
            }
        }
        declared.removeAll(INTERNAL);
        declared.removeAll(NOT_CODES);
        declared.removeAll(inContract);
        assertThat(declared).as("codes de cartão/fatura no código e fora da tabela do contrato").isEmpty();
    }

    private static Set<String> contractCodes() {
        String description = ContractValidation.spec().getInfo().getDescription();
        Set<String> codes = new TreeSet<>();
        Matcher row = ROW.matcher(description);
        while (row.find()) {
            Matcher code = CODE.matcher(row.group(2));
            while (code.find()) {
                codes.add(code.group(1));
            }
        }
        return codes;
    }

    private static String allMainSources() throws IOException {
        StringBuilder all = new StringBuilder();
        for (Path file : javaFiles()) {
            all.append(Files.readString(file)).append('\n');
        }
        return all.toString();
    }

    private static Iterable<Path> javaFiles() throws IOException {
        try (Stream<Path> walk = Files.walk(MAIN)) {
            return walk.filter(path -> path.toString().endsWith(".java")).toList();
        }
    }
}
