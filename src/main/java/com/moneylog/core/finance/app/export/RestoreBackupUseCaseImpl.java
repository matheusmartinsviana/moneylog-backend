package com.moneylog.core.finance.app.export;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.moneylog.core.account.app.gateways.AccountGateway;
import com.moneylog.core.account.domain.Account;
import com.moneylog.core.asset.app.gateways.AssetGateway;
import com.moneylog.core.card.app.gateways.CardGateway;
import com.moneylog.core.category.app.SystemCategories;
import com.moneylog.core.category.app.gateways.CategoryGateway;
import com.moneylog.core.category.app.usecase.SeedSystemCategoriesUseCase;
import com.moneylog.core.category.app.usecase.SeedSystemCategoriesUseCase.SeedSystemCategoriesCommand;
import com.moneylog.core.category.domain.Category;
import com.moneylog.core.commitment.app.gateways.CommitmentGateway;
import com.moneylog.core.debt.app.gateways.DebtGateway;
import com.moneylog.core.debt.app.gateways.PersonGateway;
import com.moneylog.core.debt.domain.Debt;
import com.moneylog.core.finance.app.export.BackupExceptions.BackupInvalidException;
import com.moneylog.core.finance.app.export.BackupExceptions.BackupSchemaUnsupportedException;
import com.moneylog.core.goal.app.gateways.GoalGateway;
import com.moneylog.core.income.app.PayrollLoanApplication;
import com.moneylog.core.income.app.PayrollLoanPlanLink;
import com.moneylog.core.income.app.gateways.IncomeProfileGateway;
import com.moneylog.core.income.app.gateways.IncomeProfileGateway.OccurrenceLink;
import com.moneylog.core.income.app.gateways.PayrollLoanGateway;
import com.moneylog.core.finance.app.gateways.PurchaseGroupBackupGateway;
import com.moneylog.core.recurrence.app.gateways.RecurrenceGateway;
import com.moneylog.core.sk.ledger.BookType;
import com.moneylog.core.sk.ledger.LedgerEntry;
import com.moneylog.core.sk.ledger.LedgerWriteGateway;
import com.moneylog.core.sk.ledger.PostingSource;
import com.moneylog.core.sk.ledger.PostingSourceType;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.core.thirdparty.app.gateways.ThirdPartyExpenseGateway;
import com.moneylog.core.thirdparty.domain.ThirdPartyExpense;
import com.moneylog.core.thirdparty.domain.ThirdPartyReceipt;
import com.moneylog.core.transaction.app.gateways.ForeignChargeGateway;
import com.moneylog.core.transaction.app.gateways.TransactionGateway;
import com.moneylog.core.transaction.domain.ForeignCharge;
import com.moneylog.core.transaction.domain.PurchaseShares;
import com.moneylog.core.transaction.domain.Transaction;
import com.moneylog.core.recurrence.domain.RecurringTransaction;
import com.moneylog.core.card.domain.CreditCard;
import com.moneylog.core.goal.domain.Goal;
import com.moneylog.core.asset.domain.Asset;
import com.moneylog.core.income.domain.IncomeProfile;
import com.moneylog.core.commitment.domain.TrackedCommitment;

public class RestoreBackupUseCaseImpl implements RestoreBackupUseCase {

    static final String DEACTIVATION_REASON = "restaurado do backup";

    private static final Set<PostingSourceType> VERBATIM_SOURCES = EnumSet.of(PostingSourceType.THIRD_PARTY_RECEIPT,
            PostingSourceType.THIRD_PARTY_RECEIPT_IN_BALANCE, PostingSourceType.THIRD_PARTY_REFUND_ADJUSTMENT,
            PostingSourceType.SHARE_PAYABLE);

    private final RestoreTargetGateway target;
    private final CategoryGateway categories;
    private final SeedSystemCategoriesUseCase seed;
    private final AccountGateway accounts;
    private final CardGateway cards;
    private final PersonGateway people;
    private final DebtGateway debts;
    private final RecurrenceGateway recurrences;
    private final TransactionGateway transactions;
    private final ForeignChargeGateway foreignCharges;
    private final LedgerWriteGateway ledger;
    private final GoalGateway goals;
    private final AssetGateway assets;
    private final IncomeProfileGateway incomeProfiles;
    private final PayrollLoanGateway payrollLoans;
    private final CommitmentGateway commitments;
    private final ThirdPartyExpenseGateway thirdParty;
    private final PurchaseGroupBackupGateway purchaseGroups;
    private final TimeProvider time;

    @SuppressWarnings("java:S107")
    public RestoreBackupUseCaseImpl(RestoreTargetGateway target, CategoryGateway categories, SeedSystemCategoriesUseCase seed,
                                    AccountGateway accounts, CardGateway cards, PersonGateway people, DebtGateway debts,
                                    RecurrenceGateway recurrences, TransactionGateway transactions, ForeignChargeGateway foreignCharges,
                                    LedgerWriteGateway ledger, GoalGateway goals, AssetGateway assets, IncomeProfileGateway incomeProfiles,
                                    PayrollLoanGateway payrollLoans, CommitmentGateway commitments, ThirdPartyExpenseGateway thirdParty,
                                    PurchaseGroupBackupGateway purchaseGroups, TimeProvider time) {
        this.target = target;
        this.categories = categories;
        this.seed = seed;
        this.accounts = accounts;
        this.cards = cards;
        this.people = people;
        this.debts = debts;
        this.recurrences = recurrences;
        this.transactions = transactions;
        this.foreignCharges = foreignCharges;
        this.ledger = ledger;
        this.goals = goals;
        this.assets = assets;
        this.incomeProfiles = incomeProfiles;
        this.payrollLoans = payrollLoans;
        this.commitments = commitments;
        this.thirdParty = thirdParty;
        this.purchaseGroups = purchaseGroups;
        this.time = time;
    }

    @Override
    public RestoreSummary handle(RestoreBackupCommand command) {
        UUID userId = command.userId();
        BackupSnapshot snapshot = command.snapshot();
        if (snapshot.schemaVersion() != BackupSnapshot.SCHEMA_VERSION && snapshot.schemaVersion() != BackupSnapshot.LEGACY_SCHEMA_VERSION) {
            throw new BackupSchemaUnsupportedException(snapshot.schemaVersion());
        }
        target.lockUser(userId);
        if (target.hasData(userId)) {
            throw new BackupExceptions.RestoreTargetNotEmptyException();
        }
        BackupExtras extras = snapshot.extras();
        References refs = References.of(userId, snapshot);
        refs.validate(snapshot);
        List<LedgerEntry> ledgerEntries = ledgerEntries(snapshot, refs);
        Instant now = time.now();

        restoreCategories(userId, snapshot, extras);
        snapshot.accounts().forEach(accounts::save);
        snapshot.cards().forEach(cards::save);
        snapshot.people().forEach(person -> people.insert(person));
        snapshot.debts().forEach(debts::insert);
        snapshot.recurrences().forEach(recurrences::save);
        for (Transaction transaction : refundsLast(snapshot.transactions())) {
            transactions.insert(transaction);
            ForeignCharge charge = extras.foreignCharges().get(transaction.id());
            if (charge != null) {
                foreignCharges.save(userId, transaction.id(), charge);
            }
        }
        writeLedger(userId, ledgerEntries, now);
        for (PayrollLoanApplication application : extras.payrollLoanApplications()) {
            payrollLoans.saveApplication(userId, application.transactionId(), application.debtId(), application.applied(),
                    application.interest(), application.principal());
        }
        snapshot.goals().forEach(goals::save);
        extras.assets().forEach(assets::insert);
        extras.incomeProfiles().forEach(incomeProfiles::insert);
        for (OccurrenceLink link : extras.incomeOccurrences()) {
            incomeProfiles.linkOccurrence(userId, link.profileId(), link.competence(), link.kind(), link.recurrenceId());
        }
        for (PayrollLoanPlanLink plan : extras.payrollLoanPlans()) {
            payrollLoans.savePlan(userId, plan.recurrenceId(), plan.plan());
        }
        extras.purchaseGroups().forEach(purchaseGroups::restore);
        extras.commitments().forEach(commitments::insert);
        extras.thirdPartyExpenses().forEach(thirdParty::insert);
        extras.thirdPartyReceipts().forEach(thirdParty::insertReceipt);
        seed.handle(new SeedSystemCategoriesCommand(userId));
        return new RestoreSummary(snapshot.schemaVersion(), counts(snapshot, extras, ledgerEntries.size()));
    }

    private void restoreCategories(UUID userId, BackupSnapshot snapshot, BackupExtras extras) {
        categories.deleteAllOf(userId);
        Set<String> usedKeys = new HashSet<>();
        for (Category category : snapshot.categories()) {
            String key = category.isSystem() ? systemKey(category, extras.categorySystemKeys().get(category.id())) : null;
            if (key != null && usedKeys.add(key)) {
                categories.saveSystem(category, key);
            } else {
                categories.save(category.isSystem()
                        ? Category.restore(category.id(), category.userId(), category.name(), category.kind(), false,
                        category.isArchived(), 0L)
                        : category);
            }
        }
    }

    private static String systemKey(Category category, String declared) {
        if (declared != null) {
            return declared;
        }
        return SystemCategories.ALL.stream().filter(d -> d.kind() == category.kind() && d.name().equals(category.name()))
                .map(SystemCategories.Definition::key).findFirst().orElse(null);
    }

    private static List<Transaction> refundsLast(List<Transaction> all) {
        List<Transaction> ordered = new ArrayList<>();
        Set<UUID> placed = new HashSet<>();
        List<Transaction> pending = new ArrayList<>(all);
        while (!pending.isEmpty()) {
            List<Transaction> next = new ArrayList<>();
            for (Transaction transaction : pending) {
                if (transaction.refundOfId() == null || placed.contains(transaction.refundOfId())) {
                    ordered.add(transaction);
                    placed.add(transaction.id());
                } else {
                    next.add(transaction);
                }
            }
            if (next.size() == pending.size()) {
                throw new BackupInvalidException("estornos de compra que apontam para movimentações inexistentes ou em ciclo.");
            }
            pending = next;
        }
        return ordered;
    }

    private static List<LedgerEntry> ledgerEntries(BackupSnapshot snapshot, References refs) {
        List<LedgerEntry> entries = new ArrayList<>();
        for (Account account : snapshot.accounts()) {
            if (!account.openingPostings().isEmpty()) {
                entries.addAll(LedgerEntry.activeGroup(PostingSource.accountOpening(account.id()), account.openingPostings()));
            }
        }
        snapshot.transactions().forEach(t -> entries.addAll(t.entries()));
        snapshot.debts().forEach(d -> entries.addAll(d.entries()));
        for (LedgerEntry entry : snapshot.ledgerPostings()) {
            if (VERBATIM_SOURCES.contains(entry.source().type())) {
                refs.requireVerbatimSource(entry);
                entries.add(entry);
            }
        }
        for (LedgerEntry entry : entries) {
            refs.requireBook(entry);
        }
        return entries;
    }

    private void writeLedger(UUID userId, List<LedgerEntry> entries, Instant now) {
        ledger.append(userId, entries.stream().map(e -> new LedgerEntry(e.source(), e.posting(), true)).toList());
        Map<List<Object>, List<LedgerEntry>> byOwner = new LinkedHashMap<>();
        for (LedgerEntry entry : entries) {
            byOwner.computeIfAbsent(List.of(entry.source().type(), entry.source().sourceId()), k -> new ArrayList<>()).add(entry);
        }
        for (List<LedgerEntry> owned : byOwner.values()) {
            if (owned.stream().noneMatch(e -> !e.active())) {
                continue;
            }
            PostingSource source = owned.get(0).source();
            if (owned.stream().noneMatch(LedgerEntry::active)) {
                ledger.deactivateBySource(userId, source.type(), source.sourceId(), DEACTIVATION_REASON, now);
                continue;
            }
            Set<UUID> inactiveInstallments = new HashSet<>();
            for (LedgerEntry entry : owned) {
                if (entry.active()) {
                    continue;
                }
                if (entry.source().installmentId() == null) {
                    throw new BackupInvalidException("lançamentos inativos sem parcela em uma origem ainda ativa (" + source.type() + ").");
                }
                inactiveInstallments.add(entry.source().installmentId());
            }
            ledger.deactivateByInstallments(userId, source.sourceId(), inactiveInstallments, DEACTIVATION_REASON, now);
        }
    }

    private static Map<String, Integer> counts(BackupSnapshot s, BackupExtras x, int ledgerPostings) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("accounts", s.accounts().size());
        counts.put("categories", s.categories().size());
        counts.put("cards", s.cards().size());
        counts.put("transactions", s.transactions().size());
        counts.put("debts", s.debts().size());
        counts.put("people", s.people().size());
        counts.put("recurrences", s.recurrences().size());
        counts.put("goals", s.goals().size());
        counts.put("ledgerPostings", ledgerPostings);
        counts.put("purchaseGroups", x.purchaseGroups().size());
        counts.put("assets", x.assets().size());
        counts.put("incomeProfiles", x.incomeProfiles().size());
        counts.put("commitments", x.commitments().size());
        counts.put("thirdPartyExpenses", x.thirdPartyExpenses().size());
        counts.put("thirdPartyReceipts", x.thirdPartyReceipts().size());
        return counts;
    }

    static final class References {

        private final Set<UUID> accountIds = new HashSet<>();
        private final Set<UUID> cardIds = new HashSet<>();
        private final Set<UUID> categoryIds = new HashSet<>();
        private final Set<UUID> personIds = new HashSet<>();
        private final Set<UUID> debtIds = new HashSet<>();
        private final Set<UUID> recurrenceIds = new HashSet<>();
        private final Set<UUID> transactionIds = new HashSet<>();
        private final Set<UUID> receiptIds = new HashSet<>();
        private final Set<UUID> profileIds = new HashSet<>();
        private final UUID userId;
        private Set<UUID> personOrDebt;

        private References(UUID userId) {
            this.userId = userId;
        }

        static References of(UUID userId, BackupSnapshot s) {
            References refs = new References(userId);
            s.accounts().forEach(a -> refs.register(refs.accountIds, a.id(), a.userId(), "conta"));
            s.cards().forEach(c -> refs.register(refs.cardIds, c.id(), c.userId(), "cartão"));
            s.categories().forEach(c -> refs.register(refs.categoryIds, c.id(), c.userId(), "categoria"));
            s.people().forEach(p -> refs.register(refs.personIds, p.id(), p.userId(), "pessoa"));
            s.debts().forEach(d -> refs.register(refs.debtIds, d.id(), d.userId(), "dívida"));
            s.recurrences().forEach(r -> refs.register(refs.recurrenceIds, r.id(), r.userId(), "recorrência"));
            s.transactions().forEach(t -> refs.register(refs.transactionIds, t.id(), t.userId(), "movimentação"));
            s.goals().forEach(g -> refs.owned(g.userId(), "meta"));
            s.extras().thirdPartyReceipts().forEach(r -> refs.register(refs.receiptIds, r.id(), r.userId(), "recebimento"));
            s.extras().incomeProfiles().forEach(p -> refs.register(refs.profileIds, p.id(), p.userId(), "perfil de renda"));
            s.extras().assets().forEach(a -> refs.owned(a.userId(), "ativo"));
            s.extras().purchaseGroups().forEach(g -> refs.owned(g.userId(), "grupo de compra"));
            s.extras().commitments().forEach(c -> refs.owned(c.userId(), "compromisso"));
            s.extras().thirdPartyExpenses().forEach(e -> refs.owned(e.userId(), "marca de terceiro"));
            return refs;
        }

        private void register(Set<UUID> ids, UUID id, UUID owner, String what) {
            owned(owner, what);
            if (!ids.add(id)) {
                throw new BackupInvalidException(what + " repetida no arquivo (" + id + ").");
            }
        }

        private void owned(UUID owner, String what) {
            if (!userId.equals(owner)) {
                throw new BackupInvalidException(what + " de outro dono.");
            }
        }

        private static void ref(Set<UUID> ids, UUID id, String what) {
            if (id != null && !ids.contains(id)) {
                throw new BackupInvalidException(what + " referenciada não existe no arquivo (" + id + ").");
            }
        }

        void validate(BackupSnapshot s) {
            for (CreditCard c : s.cards()) {
                ref(accountIds, c.defaultPaymentAccountId(), "conta de pagamento");
            }
            for (Debt d : s.debts()) {
                ref(personIds, d.personId(), "pessoa");
                ref(categoryIds, d.categoryId(), "categoria");
                ref(transactionIds, d.originTransactionId(), "movimentação de origem");
                d.settlements().forEach(x -> ref(transactionIds, x.transactionId(), "movimentação de quitação"));
            }
            for (RecurringTransaction r : s.recurrences()) {
                ref(categoryIds, r.categoryId(), "categoria");
                ref(accountIds, r.accountId(), "conta");
                ref(cardIds, r.cardId(), "cartão");
                sharesRef(r.purchaseShares());
            }
            for (Transaction t : s.transactions()) {
                ref(accountIds, t.accountId(), "conta");
                ref(accountIds, t.counterpartAccountId(), "conta de contrapartida");
                ref(cardIds, t.cardId(), "cartão");
                ref(categoryIds, t.categoryId(), "categoria");
                ref(debtIds, t.debtId(), "dívida");
                ref(transactionIds, t.refundOfId(), "movimentação estornada");
                ref(recurrenceIds, t.origin().recurrenceId(), "recorrência");
                sharesRef(t.purchaseShares());
            }
            for (Goal g : s.goals()) {
                g.reservations().forEach(r -> ref(accountIds, r.accountId(), "conta da reserva"));
            }
            BackupExtras x = s.extras();
            for (BackupPurchaseGroup g : x.purchaseGroups()) {
                g.members().forEach(m -> ref(transactionIds, m, "compra do grupo"));
            }
            for (IncomeProfile p : x.incomeProfiles()) {
                p.deductions().forEach(d -> ref(debtIds, d.debtId(), "dívida do consignado"));
            }
            for (OccurrenceLink l : x.incomeOccurrences()) {
                ref(profileIds, l.profileId(), "perfil de renda");
                ref(recurrenceIds, l.recurrenceId(), "recorrência");
            }
            for (PayrollLoanPlanLink l : x.payrollLoanPlans()) {
                ref(recurrenceIds, l.recurrenceId(), "recorrência");
                ref(debtIds, l.plan().debtId(), "dívida");
            }
            for (PayrollLoanApplication a : x.payrollLoanApplications()) {
                ref(transactionIds, a.transactionId(), "movimentação");
                ref(debtIds, a.debtId(), "dívida");
            }
            for (TrackedCommitment c : x.commitments()) {
                c.personIds().forEach(p -> ref(personIds, p, "pessoa"));
            }
            for (ThirdPartyExpense e : x.thirdPartyExpenses()) {
                ref(transactionIds, e.transactionId(), "movimentação");
                ref(personIds, e.personId(), "pessoa");
            }
            for (ThirdPartyReceipt r : x.thirdPartyReceipts()) {
                ref(transactionIds, r.transactionId(), "movimentação");
                ref(personIds, r.personId(), "pessoa");
                ref(accountIds, r.accountId(), "conta");
            }
        }

        private void sharesRef(PurchaseShares shares) {
            if (shares != null) {
                shares.personIds().forEach(p -> ref(personIds, p, "pessoa do rateio"));
            }
        }

        void requireVerbatimSource(LedgerEntry entry) {
            Set<UUID> allowed = entry.source().type() == PostingSourceType.SHARE_PAYABLE ? transactionIds : receiptIds;
            if (!allowed.contains(entry.source().sourceId())) {
                throw new BackupInvalidException("lançamento do razão sem origem no arquivo (" + entry.source().type() + ").");
            }
        }

        void requireBook(LedgerEntry entry) {
            UUID id = entry.posting().book().id();
            BookType type = entry.posting().bookType();
            Set<UUID> allowed = switch (type) {
                case ACCOUNT -> accountIds;
                case CARD -> cardIds;
                case EXPENSE, INCOME -> categoryIds;
                case RECEIVABLE, PAYABLE -> personOrDebtIds();
                case EQUITY -> null;
            };
            if (allowed != null && !allowed.contains(id)) {
                throw new BackupInvalidException("lançamento do razão em livro inexistente no arquivo (" + type + ").");
            }
        }

        private Set<UUID> personOrDebtIds() {
            if (personOrDebt == null) {
                personOrDebt = new HashSet<>(personIds);
                personOrDebt.addAll(debtIds);
            }
            return personOrDebt;
        }
    }
}
