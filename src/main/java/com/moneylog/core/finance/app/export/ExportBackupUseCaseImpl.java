package com.moneylog.core.finance.app.export;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.moneylog.core.category.app.gateways.CategoryGateway;
import com.moneylog.core.debt.app.gateways.PersonGateway;
import com.moneylog.core.debt.domain.Person;
import com.moneylog.core.finance.app.gateways.FinancialRecordsGateway;
import com.moneylog.core.finance.domain.FinancialRecords;
import com.moneylog.core.recurrence.app.gateways.RecurrenceGateway;
import com.moneylog.core.sk.page.PageQuery;
import com.moneylog.core.sk.page.Slice;
import com.moneylog.core.sk.persistence.Versioned;
import com.moneylog.core.sk.time.TimeProvider;

public class ExportBackupUseCaseImpl implements ExportBackupUseCase {

    private final FinancialRecordsGateway records;
    private final CategoryGateway categories;
    private final PersonGateway people;
    private final RecurrenceGateway recurrences;
    private final TimeProvider time;
    private final int maxTransactions;

    public ExportBackupUseCaseImpl(FinancialRecordsGateway records, CategoryGateway categories, PersonGateway people,
                                   RecurrenceGateway recurrences, TimeProvider time) {
        this(records, categories, people, recurrences, time, MAX_TRANSACTIONS);
    }

    public ExportBackupUseCaseImpl(FinancialRecordsGateway records, CategoryGateway categories, PersonGateway people,
                                   RecurrenceGateway recurrences, TimeProvider time, int maxTransactions) {
        this.maxTransactions = maxTransactions;
        this.records = records;
        this.categories = categories;
        this.people = people;
        this.recurrences = recurrences;
        this.time = time;
    }

    @Override
    public BackupSnapshot handle(ExportBackupQuery query) {
        UUID userId = query.userId();
        FinancialRecords loaded = records.load(userId);
        if (loaded.transactions().size() > maxTransactions) {
            throw new ExportTooLargeException(loaded.transactions().size(), maxTransactions);
        }
        return new BackupSnapshot(BackupSnapshot.SCHEMA_VERSION, time.now(), loaded.accounts(), categories.list(userId, null, true),
                loaded.cards(), loaded.transactions(), loaded.debts(), allPeople(userId), recurrences.findAll(userId), loaded.goals(),
                loaded.entries());
    }

    private List<Person> allPeople(UUID userId) {
        List<Person> all = new ArrayList<>();
        for (int page = 1;; page++) {
            Slice<Versioned<Person>> slice = people.list(userId, null, null, new PageQuery(page, PageQuery.MAX_PAGE_SIZE));
            slice.items().forEach(v -> all.add(v.value()));
            if (!slice.hasNext()) {
                return all;
            }
        }
    }
}
