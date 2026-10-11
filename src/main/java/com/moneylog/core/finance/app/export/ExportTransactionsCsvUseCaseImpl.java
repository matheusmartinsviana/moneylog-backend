package com.moneylog.core.finance.app.export;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

import com.moneylog.core.account.domain.Account;
import com.moneylog.core.card.domain.CreditCard;
import com.moneylog.core.category.app.gateways.CategoryGateway;
import com.moneylog.core.category.domain.Category;
import com.moneylog.core.finance.app.gateways.FinancialRecordsGateway;
import com.moneylog.core.finance.domain.FinancialRecords;
import com.moneylog.core.transaction.app.query.TransactionFilter;
import com.moneylog.core.transaction.domain.Transaction;
import com.moneylog.core.transaction.domain.TransactionStatus;

public class ExportTransactionsCsvUseCaseImpl implements ExportTransactionsCsvUseCase {

    private static final char BOM = '﻿';
    private static final String EOL = "\r\n";

    private final FinancialRecordsGateway records;
    private final CategoryGateway categories;
    private final int maxRows;

    public ExportTransactionsCsvUseCaseImpl(FinancialRecordsGateway records, CategoryGateway categories) {
        this(records, categories, ExportBackupUseCase.MAX_TRANSACTIONS);
    }

    public ExportTransactionsCsvUseCaseImpl(FinancialRecordsGateway records, CategoryGateway categories, int maxRows) {
        this.records = records;
        this.categories = categories;
        this.maxRows = maxRows;
    }

    @Override
    public CsvExport handle(ExportTransactionsCsvQuery query) {
        FinancialRecords loaded = records.load(query.userId());
        Map<UUID, String> categoryNames = categories.list(query.userId(), null, true).stream()
                .collect(Collectors.toMap(Category::id, Category::name));
        Map<UUID, String> accountNames = loaded.accounts().stream().collect(Collectors.toMap(Account::id, Account::name));
        Map<UUID, String> cardNames = loaded.cards().stream().collect(Collectors.toMap(CreditCard::id, CreditCard::name));
        TransactionFilter filter = query.filter();
        List<TransactionFilter> filters = query.includeVoided()
                ? List.of(withStatus(filter, TransactionStatus.ACTIVE), withStatus(filter, TransactionStatus.VOIDED)) : List.of(filter);
        List<Transaction> matching = loaded.transactions().stream().filter(t -> filters.stream().anyMatch(f -> f.matches(t)))
                .sorted(Comparator.comparing(Transaction::date).thenComparing(Transaction::id)).toList();
        if (matching.size() > maxRows) {
            throw new ExportTooLargeException(matching.size(), maxRows);
        }
        CsvHeaders labels = CsvHeaders.of(query.locale());
        List<String[]> rows = new ArrayList<>(matching.size());
        matching.forEach(t -> rows.add(row(t, labels, categoryNames, accountNames, cardNames)));
        return new Csv(labels.header(), rows, fileName(filter));
    }

    private static TransactionFilter withStatus(TransactionFilter f, TransactionStatus status) {
        return new TransactionFilter(f.from(), f.to(), f.type(), f.accountId(), f.categoryId(), f.method(), status, f.q());
    }

    private static String fileName(TransactionFilter f) {
        String period = f.from() == null && f.to() == null ? "" : "-" + (f.from() == null ? "" : f.from()) + "_" + (f.to() == null ? "" : f.to());
        return "moneylog-transactions" + period + ".csv";
    }

    private static String[] row(Transaction t, CsvHeaders labels, Map<UUID, String> categoryNames, Map<UUID, String> accountNames,
                                Map<UUID, String> cardNames) {
        String where = t.cardId() != null ? cardNames.get(t.cardId()) : accountNames.get(t.accountId());
        if (t.counterpartAccountId() != null) {
            where = Objects.toString(where, "") + " > " + Objects.toString(accountNames.get(t.counterpartAccountId()), "");
        }
        return new String[] {
                t.date().toString(),
                labels.type(t.type()),
                CsvCells.text(t.description()),
                CsvCells.text(t.categoryId() == null ? null : categoryNames.get(t.categoryId())),
                CsvCells.text(where),
                labels.method(t.paymentMethod()),
                CsvCells.amount(t.amount().toPlainString()),
                labels.status(t.status()),
                labels.direction(t),
                t.incomeCompetence() == null ? "" : t.incomeCompetence().toString()
        };
    }

    private record Csv(List<String> header, List<String[]> rows, String fileName) implements CsvExport {

        @Override
        public void writeTo(OutputStream out) throws IOException {
            Writer writer = new OutputStreamWriter(out, StandardCharsets.UTF_8);
            writer.write(BOM);
            writer.write(String.join(String.valueOf(CsvCells.SEPARATOR), header));
            writer.write(EOL);
            for (String[] row : rows) {
                writer.write(String.join(String.valueOf(CsvCells.SEPARATOR), row));
                writer.write(EOL);
            }
            writer.flush();
        }
    }
}
