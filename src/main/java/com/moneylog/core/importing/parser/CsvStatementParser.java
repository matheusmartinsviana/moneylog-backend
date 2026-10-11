package com.moneylog.core.importing.parser;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

import com.moneylog.core.importing.domain.ImportDirection;
import com.moneylog.core.importing.exception.ImportingExceptions.ImportFormatUnsupportedException;
import com.moneylog.core.importing.exception.ImportingExceptions.ImportMappingInvalidException;
import com.moneylog.core.importing.exception.ImportingExceptions.ImportParseFailedException;
import com.moneylog.core.importing.exception.ImportingExceptions.ImportTooLargeException;
import com.moneylog.core.importing.parser.ParsedStatement.Format;
import com.moneylog.core.importing.parser.ParsedStatement.Row;
import com.moneylog.core.importing.parser.ParsedStatement.Skipped;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;

public final class CsvStatementParser {

    public static final int MAX_BYTES = 5 * 1024 * 1024;
    public static final int MAX_LINES = 5000;

    private static final Pattern TOTAL_ROW = Pattern.compile("(?i)^(saldo|total|subtotal|resumo|limite)\\b.*");

    private CsvStatementParser() {
    }

    public record Detection(String encoding, char delimiter, boolean hasHeader, List<String> header, ColumnMapping suggested,
                            List<List<String>> preview) {
    }

    public static Detection detect(byte[] content) {
        List<List<String>> records = readAll(decode(content), delimiterOf(decode(content)), content);
        List<String> first = records.isEmpty() ? List.of() : records.get(0);
        boolean header = isHeader(first);
        return new Detection(isUtf8(content) ? "UTF-8" : "ISO-8859-1", delimiterOf(decode(content)), header,
                header ? first : List.of(), suggest(first, header, records), records.stream().limit(5).toList());
    }

    public static ParsedStatement parse(byte[] content, ColumnMapping mappingOrNull) {
        if (content == null || content.length == 0) {
            throw new ImportParseFailedException(0, "o arquivo está vazio.");
        }
        if (content.length > MAX_BYTES) {
            throw new ImportTooLargeException("o limite é 5 MB.");
        }
        String text = decode(content);
        char delimiter = mappingOrNull != null && mappingOrNull.delimiter() != null ? mappingOrNull.delimiter() : delimiterOf(text);
        List<List<String>> records = readAll(text, delimiter, content);
        if (records.size() > MAX_LINES + 1) {
            throw new ImportTooLargeException("o limite é " + MAX_LINES + " linhas.");
        }
        List<String> first = records.isEmpty() ? List.of() : records.get(0);
        ColumnMapping mapping = mappingOrNull;
        if (mapping == null) {
            boolean header = isHeader(first);
            mapping = suggest(first, header, records);
            if (mapping == null) {
                throw new ImportMappingInvalidException("não foi possível identificar as colunas de data, descrição e valor; informe o mapeamento.");
            }
        }
        List<Row> rows = new ArrayList<>();
        List<Skipped> skipped = new ArrayList<>();
        int start = mapping.hasHeader() ? 1 : 0;
        for (int i = start; i < records.size(); i++) {
            int lineNumber = i + 1;
            List<String> cells = records.get(i);
            if (cells.stream().allMatch(String::isBlank)) {
                continue;
            }
            String descriptionRaw = cell(cells, mapping.description());
            if (TOTAL_ROW.matcher(normalizeLabel(descriptionRaw)).matches() && cell(cells, mapping.date()).isBlank()) {
                skipped.add(new Skipped(lineNumber, "linha de total ou saldo"));
                continue;
            }
            Optional<java.time.LocalDate> date = DateParser.parse(cell(cells, mapping.date()));
            if (date.isEmpty()) {
                skipped.add(new Skipped(lineNumber, cell(cells, mapping.date()).isBlank() ? "linha sem data (total ou rodapé)" : "data inválida"));
                continue;
            }
            Optional<Long> cents = signedCents(cells, mapping);
            if (cents == null) {
                skipped.add(new Skipped(lineNumber, "valor inválido"));
                continue;
            }
            if (cents.isEmpty() || cents.get() == 0L) {
                skipped.add(new Skipped(lineNumber, "valor zero"));
                continue;
            }
            long value = cents.get();
            ImportDirection direction = value > 0 ? ImportDirection.IN : ImportDirection.OUT;
            if (mapping.invert() && mapping.amount() != null) {
                direction = direction == ImportDirection.IN ? ImportDirection.OUT : ImportDirection.IN;
            }
            rows.add(new Row(lineNumber, date.get(), Money.ofCents(Math.abs(value), CurrencyCode.BRL), direction,
                    descriptionRaw.strip(), null));
        }
        if (rows.isEmpty() && skipped.isEmpty()) {
            throw new ImportParseFailedException(0, "nenhuma linha de lançamento encontrada.");
        }
        return new ParsedStatement(Format.CSV, false, rows, skipped);
    }

    private static Optional<Long> signedCents(List<String> cells, ColumnMapping mapping) {
        if (mapping.amount() != null) {
            String raw = cell(cells, mapping.amount());
            if (raw.isBlank()) {
                return Optional.empty();
            }
            Optional<Long> value = AmountParser.parseCents(raw);
            return value.isEmpty() ? null : value;
        }
        long total = 0;
        boolean any = false;
        if (mapping.debit() != null && !cell(cells, mapping.debit()).isBlank()) {
            Optional<Long> debit = AmountParser.parseCents(cell(cells, mapping.debit()));
            if (debit.isEmpty()) {
                return null;
            }
            total -= Math.abs(debit.get());
            any = true;
        }
        if (mapping.credit() != null && !cell(cells, mapping.credit()).isBlank()) {
            Optional<Long> credit = AmountParser.parseCents(cell(cells, mapping.credit()));
            if (credit.isEmpty()) {
                return null;
            }
            total += Math.abs(credit.get());
            any = true;
        }
        return any ? Optional.of(total) : Optional.empty();
    }

    private static String cell(List<String> cells, int index) {
        return index < cells.size() ? cells.get(index) : "";
    }

    static boolean isUtf8(byte[] content) {
        try {
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content));
            return true;
        } catch (CharacterCodingException e) {
            return false;
        }
    }

    static String decode(byte[] content) {
        int offset = content.length >= 3 && (content[0] & 0xFF) == 0xEF && (content[1] & 0xFF) == 0xBB && (content[2] & 0xFF) == 0xBF ? 3 : 0;
        byte[] body = offset == 0 ? content : java.util.Arrays.copyOfRange(content, offset, content.length);
        return isUtf8(body) ? new String(body, StandardCharsets.UTF_8) : new String(body, StandardCharsets.ISO_8859_1);
    }

    static char delimiterOf(String text) {
        String firstLine = text.lines().filter(l -> !l.isBlank()).findFirst().orElse("");
        int semicolons = count(firstLine, ';');
        int commas = count(firstLine, ',');
        int tabs = count(firstLine, '\t');
        if (tabs > semicolons && tabs > commas) {
            return '\t';
        }
        return semicolons >= commas && semicolons > 0 ? ';' : commas > 0 ? ',' : ';';
    }

    private static int count(String text, char c) {
        int n = 0;
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '"') {
                quoted = !quoted;
            } else if (ch == c && !quoted) {
                n++;
            }
        }
        return n;
    }

    static List<List<String>> readAll(String text, char delimiter, byte[] original) {
        if (original != null && original.length > MAX_BYTES) {
            throw new ImportTooLargeException("o limite é 5 MB.");
        }
        List<List<String>> records = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        int line = 1;
        int n = text.length();
        for (int i = 0; i < n; i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < n && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    if (c == '\n') {
                        line++;
                    }
                    field.append(c);
                }
            } else if (c == '"' && field.isEmpty()) {
                quoted = true;
            } else if (c == delimiter) {
                row.add(field.toString());
                field.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < n && text.charAt(i + 1) == '\n') {
                    i++;
                }
                row.add(field.toString());
                field.setLength(0);
                records.add(row);
                row = new ArrayList<>();
                line++;
                if (records.size() > MAX_LINES + 1) {
                    throw new ImportTooLargeException("o limite é " + MAX_LINES + " linhas.");
                }
            } else {
                field.append(c);
            }
        }
        if (quoted) {
            throw new ImportParseFailedException(line, "aspas sem fechamento.");
        }
        if (!field.isEmpty() || !row.isEmpty()) {
            row.add(field.toString());
            records.add(row);
        }
        return records;
    }

    private static boolean isHeader(List<String> first) {
        if (first.isEmpty()) {
            return false;
        }
        return first.stream().noneMatch(c -> DateParser.parse(c).isPresent())
                && first.stream().noneMatch(c -> AmountParser.parseCents(c).isPresent() && c.matches(".*\\d.*"));
    }

    private static String normalizeLabel(String text) {
        return Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFD).replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT).strip();
    }

    static ColumnMapping suggest(List<String> first, boolean header, List<List<String>> records) {
        int date = -1;
        int description = -1;
        int amount = -1;
        int debit = -1;
        int credit = -1;
        if (header) {
            for (int i = 0; i < first.size(); i++) {
                String label = normalizeLabel(first.get(i));
                if (date < 0 && (label.startsWith("data") || label.equals("date") || label.contains("data "))) {
                    date = i;
                } else if (description < 0 && (label.contains("descri") || label.contains("historico") || label.contains("memo")
                        || label.equals("title") || label.equals("titulo") || label.contains("lancamento") || label.contains("estabelecimento"))) {
                    description = i;
                } else if (debit < 0 && (label.contains("debito") || label.equals("debit") || label.equals("saida"))) {
                    debit = i;
                } else if (credit < 0 && (label.contains("credito") || label.equals("credit") || label.equals("entrada"))) {
                    credit = i;
                } else if (amount < 0 && (label.contains("valor") || label.equals("amount") || label.contains("montante"))) {
                    amount = i;
                }
            }
        } else if (!records.isEmpty()) {
            List<String> sample = records.get(0);
            for (int i = 0; i < sample.size(); i++) {
                String cell = sample.get(i);
                if (date < 0 && DateParser.parse(cell).isPresent()) {
                    date = i;
                } else if (amount < 0 && AmountParser.parseCents(cell).isPresent() && cell.matches(".*\\d.*") && !cell.matches("\\d{1,2}[/.\\-]\\d{1,2}.*")) {
                    amount = i;
                } else if (description < 0 && !cell.isBlank()) {
                    description = i;
                }
            }
        }
        if (date < 0 || description < 0) {
            return null;
        }
        try {
            if (amount >= 0) {
                return new ColumnMapping(date, description, amount, null, null, false, header, null);
            }
            if (debit >= 0 || credit >= 0) {
                return new ColumnMapping(date, description, null, debit >= 0 ? debit : null, credit >= 0 ? credit : null, false, header, null);
            }
        } catch (ImportMappingInvalidException e) {
            return null;
        }
        return null;
    }

    public static void requireTextual(byte[] content) {
        for (byte b : content) {
            if (b == 0) {
                throw new ImportFormatUnsupportedException("o arquivo parece binário (XLSX e PDF ainda não são suportados).");
            }
        }
    }
}
