package com.moneylog.core.income.app.usecase;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.moneylog.core.category.app.gateways.CategoryGateway;
import com.moneylog.core.category.domain.Category;
import com.moneylog.core.category.exception.CategoryExceptions.DefaultCategoryUnavailableException;
import com.moneylog.core.income.app.GeneratedIncomeOccurrence;
import com.moneylog.core.income.app.gateways.IncomeProfileGateway;
import com.moneylog.core.income.app.PayrollLoanPlan;
import com.moneylog.core.income.app.gateways.PayrollLoanGateway;
import com.moneylog.core.income.app.gateways.TaxTableGateway;
import com.moneylog.core.income.domain.IncomePreview;
import com.moneylog.core.income.domain.IncomePreview.Payment;
import com.moneylog.core.income.domain.IncomeProfile;
import com.moneylog.core.income.domain.PayrollKind;
import com.moneylog.core.income.exception.IncomeExceptions.IncomeProfileInvalidException;
import com.moneylog.core.income.exception.IncomeExceptions.IncomeProfileNotFoundException;
import com.moneylog.core.recurrence.app.gateways.RecurrenceGateway;
import com.moneylog.core.recurrence.domain.Frequency;
import com.moneylog.core.recurrence.domain.RecurringTransaction;
import com.moneylog.core.recurrence.domain.Schedule;
import com.moneylog.core.sk.id.IdGenerator;
import com.moneylog.core.sk.time.Competence;
import com.moneylog.core.sk.time.FinancialDates;
import com.moneylog.core.transaction.domain.TransactionType;

public class GenerateIncomeOccurrencesUseCaseImpl implements GenerateIncomeOccurrencesUseCase {

    private static final String SALARY_KEY = "SALARY";

    private final IncomeProfileGateway profiles;
    private final TaxTableGateway tables;
    private final RecurrenceGateway recurrences;
    private final CategoryGateway categories;
    private final IdGenerator ids;
    private final PayrollLoanGateway payrollLoans;

    public GenerateIncomeOccurrencesUseCaseImpl(IncomeProfileGateway profiles, TaxTableGateway tables, RecurrenceGateway recurrences,
                                                CategoryGateway categories, IdGenerator ids, PayrollLoanGateway payrollLoans) {
        this.payrollLoans = payrollLoans;
        this.profiles = profiles;
        this.tables = tables;
        this.recurrences = recurrences;
        this.categories = categories;
        this.ids = ids;
    }

    @Override
    public List<GeneratedIncomeOccurrence> handle(GenerateIncomeOccurrencesCommand command) {
        validateWindow(command.from(), command.to());
        profiles.lockForUpdate(command.userId(), command.profileId());
        IncomeProfile profile = profiles.findById(command.userId(), command.profileId())
                .orElseThrow(() -> new IncomeProfileNotFoundException(command.profileId())).value();
        profile.ensureActive();
        Category category = categories.findBySystemKey(command.userId(), SALARY_KEY)
                .filter(c -> !c.isArchived()).orElseThrow(() -> new DefaultCategoryUnavailableException(SALARY_KEY));
        List<GeneratedIncomeOccurrence> result = new ArrayList<>();
        for (Competence competence = command.from(); !competence.isAfter(command.to()); competence = competence.plusMonths(1)) {
            IncomePreview preview = IncomeTaxTables.preview(tables, profile, competence);
            for (PayrollKind kind : PayrollKind.values()) {
                Payment payment = preview.payment(kind);
                if (payment == null || !payment.amount().isPositive()) {
                    continue;
                }
                result.add(generate(profile, category, competence, kind, payment, preview));
            }
        }
        return result;
    }

    private GeneratedIncomeOccurrence generate(IncomeProfile profile, Category category, Competence competence, PayrollKind kind,
                                               Payment payment, IncomePreview preview) {
        Optional<UUID> existing = profiles.findOccurrence(profile.userId(), profile.id(), competence, kind);
        if (existing.isPresent()) {
            RecurringTransaction stored = recurrences.findById(profile.userId(), existing.get()).orElse(null);
            if (stored != null) {
                return new GeneratedIncomeOccurrence(kind, competence, stored.schedule().startDate(), stored.amount(), stored.id(), false);
            }
        }
        FinancialDates.requireInWindow(payment.date(), "date");
        int offset = (int) ChronoUnit.MONTHS.between(Competence.from(payment.date()).firstDay(), competence.firstDay());
        UUID recurrenceId = ids.next();
        Schedule schedule = new Schedule(Frequency.MONTHLY, 1, null, payment.date(), null, 1);
        RecurringTransaction recurrence = RecurringTransaction.ofAccount(recurrenceId, profile.userId(), TransactionType.INCOME,
                payment.amount(), describe(profile, kind, competence), category.id(), null, null, schedule, offset);
        recurrence.defineProvenance(profile.provenance());
        recurrences.save(recurrence);
        if (kind == PayrollKind.BALANCE) {
            loanPlanOf(preview).ifPresent(plan -> payrollLoans.savePlan(profile.userId(), recurrenceId, plan));
        }
        profiles.linkOccurrence(profile.userId(), profile.id(), competence, kind, recurrenceId);
        return new GeneratedIncomeOccurrence(kind, competence, payment.date(), payment.amount(), recurrenceId, true);
    }

    private static Optional<PayrollLoanPlan> loanPlanOf(IncomePreview preview) {
        List<IncomePreview.Deduction> linked = preview.loanDeductions().stream().filter(d -> d.debtId() != null).toList();
        if (linked.size() > 1) {
            throw new IncomeProfileInvalidException("só um consignado vinculado a dívida por competência; há " + linked.size()
                    + " em " + preview.month() + ".");
        }
        return linked.stream().findFirst().map(d -> new PayrollLoanPlan(d.debtId(), d.amount(), d.interestAmount()));
    }

    private static String describe(IncomeProfile profile, PayrollKind kind, Competence competence) {
        String label = switch (kind) {
            case ADVANCE -> "adiantamento";
            case BALANCE -> "saldo";
            case THIRTEENTH_FIRST -> "13º, 1ª parcela";
        };
        String text = profile.source() + " (" + label + ") " + competence;
        return text.length() > 200 ? text.substring(0, 200) : text;
    }

    private static void validateWindow(Competence from, Competence to) {
        if (from.isAfter(to)) {
            throw new IncomeProfileInvalidException("'from' não pode ser posterior a 'to'.");
        }
        long months = ChronoUnit.MONTHS.between(from.firstDay(), to.firstDay()) + 1;
        if (months > MAX_MONTHS) {
            throw new IncomeProfileInvalidException("a janela vai de 1 a " + MAX_MONTHS + " meses.");
        }
    }
}
