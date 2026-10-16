package com.moneylog.core.finance.app.statement;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.moneylog.core.category.app.gateways.CategoryGateway;
import com.moneylog.core.category.domain.Category;
import com.moneylog.core.commitment.app.gateways.CommitmentGateway;
import com.moneylog.core.commitment.domain.CommitmentKind;
import com.moneylog.core.commitment.domain.ScheduleItem;
import com.moneylog.core.commitment.domain.ScheduleItemType;
import com.moneylog.core.commitment.domain.TrackedCommitment;
import com.moneylog.core.finance.app.gateways.CommitmentSourcesGateway;
import com.moneylog.core.finance.app.gateways.FinancialRecordsGateway;
import com.moneylog.core.finance.domain.CardShareReceivables;
import com.moneylog.core.finance.domain.FinancialRecords;
import com.moneylog.core.finance.domain.OwnerIncomeInputs;
import com.moneylog.core.finance.domain.OwnerStatement;
import com.moneylog.core.finance.domain.RealEstateItem;
import com.moneylog.core.income.app.gateways.IncomeProfileGateway;
import com.moneylog.core.income.app.gateways.TaxTableGateway;
import com.moneylog.core.income.app.usecase.IncomeTaxTables;
import com.moneylog.core.income.domain.IncomePreview;
import com.moneylog.core.income.domain.IncomeProfile;
import com.moneylog.core.income.domain.PayrollKind;
import com.moneylog.core.sk.exception.DomainException;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.time.Competence;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.core.thirdparty.app.gateways.ThirdPartyExpenseGateway;

public class GetOwnerStatementUseCaseImpl implements GetOwnerStatementUseCase {

    private static final String SALARY_KEY = "SALARY";

    private final FinancialRecordsGateway records;
    private final CommitmentSourcesGateway recurrences;
    private final CommitmentGateway commitments;
    private final IncomeProfileGateway profiles;
    private final TaxTableGateway tables;
    private final CategoryGateway categories;
    private final ThirdPartyExpenseGateway marks;
    private final TimeProvider time;
    private final int incomeLagMonths;

    @SuppressWarnings("java:S107")
    public GetOwnerStatementUseCaseImpl(FinancialRecordsGateway records, CommitmentSourcesGateway recurrences,
                                        CommitmentGateway commitments, IncomeProfileGateway profiles, TaxTableGateway tables,
                                        CategoryGateway categories, ThirdPartyExpenseGateway marks, TimeProvider time,
                                        int incomeLagMonths) {
        this.records = records;
        this.recurrences = recurrences;
        this.commitments = commitments;
        this.profiles = profiles;
        this.tables = tables;
        this.categories = categories;
        this.marks = marks;
        this.time = time;
        this.incomeLagMonths = incomeLagMonths;
    }

    @Override
    public OwnerStatement handle(GetOwnerStatementQuery query) {
        UUID userId = query.userId();
        LocalDate asOf = query.asOf() == null ? time.today() : query.asOf();
        Competence from = query.from() == null ? Competence.from(time.today()) : query.from();
        int months = query.months() == null ? OwnerStatement.DEFAULT_MONTHS : query.months();
        if (months < OwnerStatement.MIN_MONTHS || months > OwnerStatement.MAX_MONTHS) {
            throw new IllegalArgumentException("months fora de 1..12: " + months);
        }
        FinancialRecords loaded = records.load(userId);
        List<OwnerStatement.Warning> warnings = new ArrayList<>();
        OwnerIncomeInputs income = incomeInputs(userId, from, months, warnings);
        return OwnerStatement.of(loaded, recurrences.recurrences(userId), realEstate(userId), income, marks.findAllReceipts(userId),
                CardShareReceivables.expectedMonthsOf(marks.findExpectedMonths(userId)), from, months, asOf, incomeLagMonths, warnings);
    }

    private List<RealEstateItem> realEstate(UUID userId) {
        List<RealEstateItem> items = new ArrayList<>();
        for (TrackedCommitment commitment : commitments.findAll(userId)) {
            if (commitment.kind() != CommitmentKind.LONG_TERM_PROJECT) {
                continue;
            }
            for (ScheduleItem item : commitment.schedule()) {
                if (!item.isPaid() && item.dueDate() != null && item.type() != ScheduleItemType.FINANCING) {
                    items.add(new RealEstateItem(commitment.id(), item.position(), item.dueDate(), item.amount(), item.ownerShare(),
                            item.sharesDefined()));
                }
            }
        }
        return items;
    }

    private OwnerIncomeInputs incomeInputs(UUID userId, Competence from, int months, List<OwnerStatement.Warning> warnings) {
        UUID salaryCategoryId = categories.findBySystemKey(userId, SALARY_KEY).map(Category::id).orElse(null);
        List<OwnerIncomeInputs.Link> links = profiles.occurrenceLinks(userId).stream()
                .map(l -> new OwnerIncomeInputs.Link(l.profileId(), l.competence(), l.kind(), l.recurrenceId())).toList();
        List<OwnerIncomeInputs.Preview> previews = new ArrayList<>();
        Map<Competence, Money> loanApplied = new HashMap<>();
        List<IncomeProfile> active = profiles.activeProfiles(userId);
        for (int i = 0; i < months; i++) {
            Competence column = from.plusMonths(i);
            Competence earned = column.minusMonths(incomeLagMonths);
            for (IncomeProfile profile : active) {
                preview(profile, earned, column, previews, loanApplied, warnings);
            }
        }
        return new OwnerIncomeInputs(salaryCategoryId, links, previews, loanApplied);
    }

    private void preview(IncomeProfile profile, Competence earned, Competence column, List<OwnerIncomeInputs.Preview> previews,
                         Map<Competence, Money> loanApplied, List<OwnerStatement.Warning> warnings) {
        IncomePreview preview;
        try {
            preview = IncomeTaxTables.preview(tables, profile, earned);
        } catch (DomainException e) {
            warnings.add(new OwnerStatement.Warning(e.code(), column));
            return;
        }
        boolean paid = false;
        for (PayrollKind kind : PayrollKind.values()) {
            IncomePreview.Payment payment = preview.payment(kind);
            if (payment != null && payment.amount().isPositive()) {
                previews.add(new OwnerIncomeInputs.Preview(profile.id(), earned, kind, payment.date(), payment.amount(), profile.provenance()));
                paid |= kind != PayrollKind.THIRTEENTH_FIRST;
            }
        }
        if (paid) {
            Money loans = Money.sum(preview.loanDeductions().stream().map(IncomePreview.Deduction::amount).toList(), CurrencyCode.BRL);
            loanApplied.merge(earned, loans, Money::add);
        }
    }
}
