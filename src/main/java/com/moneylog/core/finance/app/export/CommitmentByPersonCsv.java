package com.moneylog.core.finance.app.export;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;


import com.moneylog.core.commitment.app.CommitmentByPersonView;
import com.moneylog.core.commitment.domain.PersonTotals;

public final class CommitmentByPersonCsv implements CsvExport {

    private static final char BOM = '﻿';
    private static final String EOL = "\r\n";
    private static final List<String> HEADER_PT = List.of("Posição", "Descrição", "Tipo", "Vencimento", "Pessoa", "Valor", "Pago");
    private static final List<String> HEADER_EN = List.of("Position", "Description", "Type", "Due date", "Person", "Amount", "Paid");

    private final CommitmentByPersonView view;
    private final boolean english;

    public CommitmentByPersonCsv(CommitmentByPersonView view, Locale locale) {
        this.view = view;
        this.english = locale != null && "en".equalsIgnoreCase(locale.getLanguage());
    }

    @Override
    public String fileName() {
        return "moneylog-imovel-por-pessoa.csv";
    }

    @Override
    public void writeTo(OutputStream out) throws IOException {
        Writer writer = new OutputStreamWriter(out, StandardCharsets.UTF_8);
        writer.write(BOM);
        writeLine(writer, english ? HEADER_EN : HEADER_PT);
        for (PersonTotals person : view.people()) {
            String who = person.personId() == null ? (english ? "Me" : "Eu") : view.personNames().getOrDefault(person.personId(), "");
            for (PersonTotals.PersonItem item : person.items()) {
                writeLine(writer, List.of(String.valueOf(item.position()), CsvCells.text(item.label()), item.type().name(),
                        item.dueDate() == null ? "" : item.dueDate().toString(), CsvCells.text(who),
                        CsvCells.amount(item.amount().toPlainString()), paid(item.paid())));
            }
        }
        writer.flush();
    }

    private String paid(boolean paid) {
        return english ? (paid ? "Yes" : "No") : (paid ? "Sim" : "Não");
    }

    private static void writeLine(Writer writer, List<String> cells) throws IOException {
        writer.write(String.join(String.valueOf(CsvCells.SEPARATOR), cells));
        writer.write(EOL);
    }
}
