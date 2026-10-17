package com.moneylog.core.finance.app.transactions;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.moneylog.core.account.app.gateways.AccountGateway;
import com.moneylog.core.account.domain.Account;
import com.moneylog.core.account.exception.AccountExceptions.AccountNotFoundException;
import com.moneylog.core.card.app.gateways.CardGateway;
import com.moneylog.core.card.domain.CreditCard;
import com.moneylog.core.card.domain.InvoiceChain;
import com.moneylog.core.card.domain.InvoiceIds;
import com.moneylog.core.card.domain.InvoicePeriod;
import com.moneylog.core.card.domain.InvoiceStatus;
import com.moneylog.core.card.domain.TransactionInstallment;
import com.moneylog.core.card.exception.CardExceptions.CardNotFoundException;
import com.moneylog.core.category.app.gateways.CategoryGateway;
import com.moneylog.core.category.domain.Category;
import com.moneylog.core.category.exception.CategoryExceptions.CategoryNotFoundException;
import com.moneylog.core.debt.app.DebtSummary;
import com.moneylog.core.debt.app.gateways.DebtGateway;
import com.moneylog.core.debt.app.gateways.PersonGateway;
import com.moneylog.core.debt.domain.Debt;
import com.moneylog.core.debt.domain.Person;
import com.moneylog.core.sk.persistence.Versioned;
import com.moneylog.core.finance.app.cards.CardViews;
import com.moneylog.core.finance.app.cards.InvoiceView;
import com.moneylog.core.finance.app.transactions.TransactionDetails.CardInfo;
import com.moneylog.core.finance.app.transactions.TransactionDetails.Flags;
import com.moneylog.core.finance.app.transactions.TransactionDetails.InstallmentView;
import com.moneylog.core.finance.domain.FinancialRecords;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.persistence.Persisted;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.core.transaction.app.gateways.ForeignChargeGateway;
import com.moneylog.core.transaction.app.gateways.TransactionGateway;
import com.moneylog.core.transaction.domain.ForeignCharge;
import com.moneylog.core.transaction.domain.CardInvoices;
import com.moneylog.core.transaction.domain.Transaction;
import com.moneylog.core.transaction.domain.TransactionType;

public class TransactionDetailsAssembler {

    private final TransactionGateway transactions;
    private final AccountGateway accounts;
    private final CategoryGateway categories;
    private final CardGateway cards;
    private final TimeProvider time;
    private final DebtGateway debts;
    private final PersonGateway persons;
    private final ForeignChargeGateway foreignCharges;

    public TransactionDetailsAssembler(TransactionGateway transactions, AccountGateway accounts, CategoryGateway categories) {
        this(transactions, accounts, categories, null, null);
    }

    public TransactionDetailsAssembler(TransactionGateway transactions, AccountGateway accounts, CategoryGateway categories,
                                       CardGateway cards, TimeProvider time) {
        this(transactions, accounts, categories, cards, time, null, null);
    }

    @SuppressWarnings("java:S107")
    public TransactionDetailsAssembler(TransactionGateway transactions, AccountGateway accounts, CategoryGateway categories,
                                       CardGateway cards, TimeProvider time, DebtGateway debts, PersonGateway persons) {
        this(transactions, accounts, categories, cards, time, debts, persons, null);
    }

    @SuppressWarnings("java:S107")
    public TransactionDetailsAssembler(TransactionGateway transactions, AccountGateway accounts, CategoryGateway categories,
                                       CardGateway cards, TimeProvider time, DebtGateway debts, PersonGateway persons,
                                       ForeignChargeGateway foreignCharges) {
        this.foreignCharges = foreignCharges;
        this.debts = debts;
        this.persons = persons;
        this.transactions = transactions;
        this.accounts = accounts;
        this.categories = categories;
        this.cards = cards;
        this.time = time;
    }

    public TransactionDetails assemble(Persisted<Transaction> persisted) {
        Transaction transaction = persisted.value();
        UUID userId = transaction.userId();
        attachForeignCharge(transaction);
        Map<UUID, Account> touched = new HashMap<>();
        Account account = load(userId, transaction.accountId(), touched);
        Account counterpart = load(userId, transaction.counterpartAccountId(), touched);
        Category category = transaction.categoryId() == null ? null : categories.findById(userId, transaction.categoryId())
                .orElseThrow(() -> new CategoryNotFoundException(transaction.categoryId()));
        String refundOfDescription = null;
        if (transaction.refundOfId() != null) {
            refundOfDescription = transactions.findById(userId, transaction.refundOfId())
                    .map(original -> original.value().description()).orElse(null);
        }
        boolean hasActiveRefunds = refundable(transaction)
                && transactions.sumActiveRefundsOf(userId, transaction.id()).isPositive();
        CardInfo cardInfo = null;
        Money commitment = Money.zero(CurrencyCode.BRL);
        Flags flags = null;
        if (transaction.cardId() != null && cards != null) {
            CreditCard card = cards.findById(userId, transaction.cardId()).map(Persisted::value)
                    .orElseThrow(() -> new CardNotFoundException(transaction.cardId()));
            InvoiceChain chain = CardInvoices.chainFor(card, transactions.findAllOfCard(userId, card.id()));
            cardInfo = cardInfo(transaction, card, chain, time.today());
            commitment = commitment(transaction, chain, time.today());
            flags = TransactionFlags.ofCard(transaction, touched.values(), hasActiveRefunds, chain, time.today());
        }
        List<Debt> linked = linkedDebts(userId, transaction);
        List<DebtSummary> summaries = summaries(userId, linked);
        if (flags == null && !linked.isEmpty()) {
            flags = debtFlags(transaction, touched.values(), hasActiveRefunds, linked);
        }
        return new TransactionDetails(transaction, persisted.createdAt(), persisted.updatedAt(), account, counterpart, category,
                refundOfDescription, TransactionImpactCalculator.of(transaction, touched, today(), commitment),
                flags != null ? flags : TransactionFlags.of(transaction, touched.values(), hasActiveRefunds), cardInfo, summaries);
    }

    private void attachForeignCharge(Transaction transaction) {
        if (foreignCharges == null || transaction.foreignCharge() != null) {
            return;
        }
        ForeignCharge charge = foreignCharges.findByTransactions(transaction.userId(), List.of(transaction.id())).get(transaction.id());
        if (charge != null) {
            transaction.attachForeignCharge(charge);
        }
    }

    private void attachForeignCharges(UUID userId, List<Persisted<Transaction>> items) {
        if (foreignCharges == null || items.isEmpty()) {
            return;
        }
        Map<UUID, ForeignCharge> found = foreignCharges.findByTransactions(userId, items.stream().map(i -> i.value().id()).toList());
        for (Persisted<Transaction> item : items) {
            ForeignCharge charge = found.get(item.value().id());
            if (charge != null && item.value().foreignCharge() == null) {
                item.value().attachForeignCharge(charge);
            }
        }
    }

    private List<Debt> linkedDebts(UUID userId, Transaction transaction) {
        if (debts == null) {
            return List.of();
        }
        if (transaction.debtId() != null) {
            return debts.findById(userId, transaction.debtId()).map(Versioned::value).map(List::of).orElse(List.of());
        }
        if (transaction.type() == TransactionType.EXPENSE) {
            return debts.findByOriginTransaction(userId, transaction.id()).stream().map(Versioned::value).toList();
        }
        return List.of();
    }

    private List<DebtSummary> summaries(UUID userId, List<Debt> linked) {
        if (linked.isEmpty()) {
            return List.of();
        }
        Map<UUID, Person> people = persons.findByIds(userId, linked.stream().map(Debt::personId).filter(java.util.Objects::nonNull).distinct().toList());
        return linked.stream().map(debt -> DebtSummary.of(debt, debt.personId() == null ? null : people.get(debt.personId()), time.today())).toList();
    }

    private static Flags debtFlags(Transaction transaction, java.util.Collection<Account> touched, boolean hasActiveRefunds,
                                   List<Debt> linked) {
        return transaction.debtId() != null ? TransactionFlags.ofDebt(transaction, touched, linked.get(0))
                : TransactionFlags.ofShares(transaction, touched, hasActiveRefunds, linked);
    }

    public List<TransactionDetails> assembleAll(UUID userId, List<Persisted<Transaction>> items, FinancialRecords records) {
        Map<UUID, Account> accountsById = records.accounts().stream().collect(Collectors.toMap(Account::id, Function.identity()));
        Map<UUID, Category> categoriesById = categories.list(userId, null, true).stream()
                .collect(Collectors.toMap(Category::id, Function.identity()));
        Map<UUID, Transaction> transactionsById = records.transactions().stream()
                .collect(Collectors.toMap(Transaction::id, Function.identity()));
        Map<UUID, CreditCard> cardsById = records.cards().stream().collect(Collectors.toMap(CreditCard::id, Function.identity()));
        Map<UUID, InvoiceChain> chains = new HashMap<>();
        Map<UUID, Money> activeRefunds = new HashMap<>();
        for (Transaction t : records.transactions()) {
            if (t.type() == TransactionType.REFUND && t.isActive() && t.refundOfId() != null) {
                activeRefunds.merge(t.refundOfId(), t.amount(), Money::add);
            }
        }
        Map<UUID, Debt> debtsById = new HashMap<>();
        Map<UUID, List<Debt>> debtsByOrigin = new HashMap<>();
        for (Debt debt : records.debts()) {
            debtsById.put(debt.id(), debt);
            if (debt.originTransactionId() != null) {
                debtsByOrigin.computeIfAbsent(debt.originTransactionId(), id -> new ArrayList<>()).add(debt);
            }
        }
        Map<UUID, Person> people = persons == null || records.debts().isEmpty() ? Map.of()
                : persons.findByIds(userId, records.debts().stream().map(Debt::personId).filter(java.util.Objects::nonNull).distinct().toList());
        attachForeignCharges(userId, items);
        List<TransactionDetails> details = new ArrayList<>();
        for (Persisted<Transaction> persisted : items) {
            Transaction transaction = persisted.value();
            Map<UUID, Account> touched = new HashMap<>();
            Account account = pick(accountsById, transaction.accountId(), touched);
            Account counterpart = pick(accountsById, transaction.counterpartAccountId(), touched);
            Category category = transaction.categoryId() == null ? null : categoriesById.get(transaction.categoryId());
            if (transaction.categoryId() != null && category == null) {
                throw new CategoryNotFoundException(transaction.categoryId());
            }
            Transaction original = transaction.refundOfId() == null ? null : transactionsById.get(transaction.refundOfId());
            boolean hasActiveRefunds = refundable(transaction)
                    && activeRefunds.getOrDefault(transaction.id(), Money.zero(transaction.amount().currency())).isPositive();
            CardInfo cardInfo = null;
            Money commitment = Money.zero(CurrencyCode.BRL);
            Flags flags = null;
            if (transaction.cardId() != null && time != null) {
                CreditCard card = cardsById.get(transaction.cardId());
                if (card == null) {
                    throw new CardNotFoundException(transaction.cardId());
                }
                InvoiceChain chain = chains.computeIfAbsent(card.id(), id -> CardViews.chainOf(card, records));
                cardInfo = cardInfo(transaction, card, chain, time.today());
                commitment = commitment(transaction, chain, time.today());
                flags = TransactionFlags.ofCard(transaction, touched.values(), hasActiveRefunds, chain, time.today());
            }
            List<Debt> linked = persons == null ? List.<Debt>of() : transaction.debtId() != null
                    ? (debtsById.containsKey(transaction.debtId()) ? List.of(debtsById.get(transaction.debtId())) : List.<Debt>of())
                    : transaction.type() == TransactionType.EXPENSE ? debtsByOrigin.getOrDefault(transaction.id(), List.of()) : List.<Debt>of();
            if (flags == null && !linked.isEmpty()) {
                flags = debtFlags(transaction, touched.values(), hasActiveRefunds, linked);
            }
            List<DebtSummary> summaries = linked.stream().map(debt -> DebtSummary.of(debt, debt.personId() == null ? null : people.get(debt.personId()), time.today())).toList();
            details.add(new TransactionDetails(transaction, persisted.createdAt(), persisted.updatedAt(), account, counterpart, category,
                    original == null ? null : original.description(),
                    TransactionImpactCalculator.of(transaction, touched, today(), commitment),
                    flags != null ? flags : TransactionFlags.of(transaction, touched.values(), hasActiveRefunds), cardInfo, summaries));
        }
        return details;
    }

    private static boolean refundable(Transaction transaction) {
        return transaction.type() == TransactionType.EXPENSE || transaction.type() == TransactionType.CARD_PURCHASE;
    }

    private LocalDate today() {
        return time == null ? null : time.today();
    }

    private static CardInfo cardInfo(Transaction transaction, CreditCard card, InvoiceChain chain, LocalDate today) {
        List<InstallmentView> installments = new ArrayList<>();
        for (TransactionInstallment i : transaction.installments()) {
            InvoicePeriod period = chain.calendar().periodClosingIn(YearMonth.from(i.invoiceClosingDate()));
            installments.add(new InstallmentView(i.number(), i.total(), i.amount(), i.competence(),
                    InvoiceIds.of(card.id(), i.invoiceClosingDate()), period.referenceMonth(), period.dueDate(),
                    i.statusFor(chain.statusOf(i.invoiceClosingDate(), today)), i.lateEntry()));
        }
        InvoiceView invoice = transaction.invoiceClosingDate() == null ? null
                : InvoiceView.of(card.id(), chain.invoice(transaction.invoiceClosingDate()), today);
        return new CardInfo(card, installments, invoice);
    }

    private static Money commitment(Transaction transaction, InvoiceChain chain, LocalDate today) {
        Money zero = Money.zero(CurrencyCode.BRL);
        if (!transaction.isActive() || transaction.type() != TransactionType.CARD_PURCHASE) {
            return zero;
        }
        return Money.sum(transaction.installments().stream().filter(i -> !i.isCanceled())
                .filter(i -> chain.statusOf(i.invoiceClosingDate(), today) == InvoiceStatus.FUTURE)
                .map(TransactionInstallment::amount).toList(), CurrencyCode.BRL);
    }

    private static Account pick(Map<UUID, Account> accountsById, UUID accountId, Map<UUID, Account> touched) {
        if (accountId == null) {
            return null;
        }
        Account account = accountsById.get(accountId);
        if (account == null) {
            throw new AccountNotFoundException(accountId);
        }
        touched.put(accountId, account);
        return account;
    }

    private Account load(UUID userId, UUID accountId, Map<UUID, Account> touched) {
        if (accountId == null) {
            return null;
        }
        Account account = accounts.findById(userId, accountId).map(Persisted::value)
                .orElseThrow(() -> new AccountNotFoundException(accountId));
        touched.put(accountId, account);
        return account;
    }

}
