package com.moneylog.core.importing.parser;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.moneylog.core.importing.domain.ImportDirection;
import com.moneylog.core.importing.exception.ImportingExceptions.ImportFormatUnsupportedException;
import com.moneylog.core.importing.exception.ImportingExceptions.ImportParseFailedException;
import com.moneylog.core.importing.exception.ImportingExceptions.ImportTooLargeException;
import com.moneylog.core.importing.parser.ParsedStatement.Format;
import com.moneylog.core.importing.parser.ParsedStatement.Row;
import com.moneylog.core.importing.parser.ParsedStatement.Skipped;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;

public final class OfxStatementParser {

    static final int MAX_FIT_ID_LENGTH = 100;
    private static final Pattern STMTTRN = Pattern.compile("<STMTTRN>(.*?)(?=</STMTTRN>|<STMTTRN>|</BANKTRANLIST>|</OFX>|$)", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Set<String> GENERIC_NAMES = Set.of("", "PAGAMENTO", "COMPRA", "TRANSFERENCIA", "TRANSFERÊNCIA", "DEBITO", "DÉBITO",
            "CREDITO", "CRÉDITO", "PIX", "TED", "DOC", "TARIFA", "OUTROS", "DEBIT", "CREDIT", "PAYMENT", "XFER");

    private OfxStatementParser() {
    }

    public static ParsedStatement parse(byte[] content) {
        if (content == null || content.length == 0) {
            throw new ImportParseFailedException(0, "o arquivo está vazio.");
        }
        if (content.length > CsvStatementParser.MAX_BYTES) {
            throw new ImportTooLargeException("o limite é 5 MB.");
        }
        String text = decode(content);
        String upper = text.toUpperCase(Locale.ROOT);
        if (upper.contains("<!DOCTYPE") || upper.contains("<!ENTITY")) {
            throw new ImportParseFailedException(0, "declaração de DOCTYPE/ENTITY não é aceita em OFX.");
        }
        if (!upper.contains("<OFX>")) {
            throw new ImportParseFailedException(0, "não é um arquivo OFX (marca <OFX> ausente).");
        }
        if (!upper.contains("</OFX>")) {
            throw new ImportParseFailedException(0, "arquivo OFX truncado (falta </OFX>).");
        }
        Optional<String> currency = leaf(text, "CURDEF");
        if (currency.isPresent() && !"BRL".equalsIgnoreCase(currency.get())) {
            throw new ImportFormatUnsupportedException("moeda " + currency.get().replaceAll("[^A-Za-z]", "") + " (só BRL).");
        }
        boolean card = upper.contains("<CREDITCARDMSGSRSV1>");
        List<Row> rows = new ArrayList<>();
        List<Skipped> skipped = new ArrayList<>();
        Matcher blocks = STMTTRN.matcher(text);
        int index = 0;
        while (blocks.find()) {
            index++;
            String block = blocks.group(1);
            Optional<java.time.LocalDate> date = leaf(block, "DTPOSTED").flatMap(OfxStatementParser::ofxDate);
            Optional<String> amountText = leaf(block, "TRNAMT");
            if (date.isEmpty()) {
                skipped.add(new Skipped(index, "data inválida (DTPOSTED)"));
                continue;
            }
            Optional<Long> cents = amountText.flatMap(OfxStatementParser::ofxCents);
            if (cents.isEmpty()) {
                skipped.add(new Skipped(index, "valor inválido (TRNAMT)"));
                continue;
            }
            if (cents.get() == 0L) {
                skipped.add(new Skipped(index, "valor zero"));
                continue;
            }
            String fitId = leaf(block, "FITID").map(String::strip).filter(f -> !f.isEmpty()).orElse(null);
            if (fitId != null && fitId.length() > MAX_FIT_ID_LENGTH) {
                skipped.add(new Skipped(index, "identificador (FITID) maior que " + MAX_FIT_ID_LENGTH + " caracteres"));
                continue;
            }
            String name = leaf(block, "NAME").orElse("").strip();
            String memo = leaf(block, "MEMO").orElse("").strip();
            String description = GENERIC_NAMES.contains(name.toUpperCase(Locale.ROOT)) && !memo.isEmpty() ? memo : name.isEmpty() ? memo : name;
            ImportDirection direction = cents.get() > 0 ? ImportDirection.IN : ImportDirection.OUT;
            rows.add(new Row(index, date.get(), Money.ofCents(Math.abs(cents.get()), CurrencyCode.BRL), direction, description, fitId));
        }
        if (index == 0 && !upper.contains("<BANKTRANLIST>") && !upper.contains("<STMTRS>") && !upper.contains("<CCSTMTRS>")) {
            throw new ImportParseFailedException(0, "nenhuma transação (STMTTRN) encontrada.");
        }
        return new ParsedStatement(Format.OFX, card, rows, skipped);
    }

    static Optional<String> leaf(String text, String tag) {
        Matcher m = Pattern.compile("<" + tag + ">([^<\\r\\n]*)", Pattern.CASE_INSENSITIVE).matcher(text);
        return m.find() ? Optional.of(unescape(m.group(1)).strip()) : Optional.empty();
    }

    private static String unescape(String s) {
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&");
    }

    static Optional<java.time.LocalDate> ofxDate(String raw) {
        if (raw == null || raw.length() < 8 || !raw.substring(0, 8).matches("\\d{8}")) {
            return Optional.empty();
        }
        return DateParser.parse(raw.substring(0, 8));
    }

    static Optional<Long> ofxCents(String raw) {
        String text = raw.strip();
        if (!text.matches("[+-]?[0-9]+([.,][0-9]{1,2})?")) {
            return Optional.empty();
        }
        return AmountParser.parseCents(text.replace(',', '.'));
    }

    private static String decode(byte[] content) {
        String head = new String(content, 0, Math.min(content.length, 600), java.nio.charset.StandardCharsets.ISO_8859_1).toUpperCase(Locale.ROOT);
        Charset charset;
        if (head.contains("CHARSET:1252") || head.contains("ENCODING:USASCII")) {
            charset = Charset.forName("windows-1252");
        } else if (CsvStatementParser.isUtf8(content)) {
            charset = java.nio.charset.StandardCharsets.UTF_8;
        } else {
            charset = java.nio.charset.StandardCharsets.ISO_8859_1;
        }
        String text = new String(content, charset);
        return text.startsWith("﻿") ? text.substring(1) : text;
    }
}
