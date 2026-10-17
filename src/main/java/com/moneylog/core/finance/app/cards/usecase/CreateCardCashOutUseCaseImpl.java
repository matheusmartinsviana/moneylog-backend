package com.moneylog.core.finance.app.cards.usecase;

import java.time.LocalDate;
import java.util.UUID;

import com.moneylog.core.account.app.gateways.AccountGateway;
import com.moneylog.core.account.domain.Account;
import com.moneylog.core.account.exception.AccountExceptions.AccountNotFoundException;
import com.moneylog.core.card.app.gateways.CardGateway;
import com.moneylog.core.card.domain.CreditCard;
import com.moneylog.core.card.domain.InvoiceChain;
import com.moneylog.core.card.exception.CardExceptions.CardNotFoundException;
import com.moneylog.core.category.app.SystemCategories;
import com.moneylog.core.category.app.gateways.CategoryGateway;
import com.moneylog.core.category.domain.Category;
import com.moneylog.core.category.exception.CategoryExceptions.CategoryNotFoundException;
import com.moneylog.core.category.exception.CategoryExceptions.DefaultCategoryUnavailableException;
import com.moneylog.core.debt.app.DebtView;
import com.moneylog.core.debt.app.DebtViews;
import com.moneylog.core.debt.app.gateways.DebtGateway;
import com.moneylog.core.debt.app.gateways.PersonGateway;
import com.moneylog.core.debt.domain.Debt;
import com.moneylog.core.debt.domain.Person;
import com.moneylog.core.debt.exception.DebtExceptions.DebtNotFoundException;
import com.moneylog.core.debt.exception.DebtExceptions.PersonNotFoundException;
import com.moneylog.core.sk.ledger.LedgerWriteGateway;
import com.moneylog.core.sk.id.IdGenerator;
import com.moneylog.core.sk.persistence.Persisted;
import com.moneylog.core.sk.persistence.Versioned;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.core.transaction.app.IdempotentClaim;
import com.moneylog.core.transaction.app.gateways.IdempotencyGateway;
import com.moneylog.core.transaction.app.gateways.IdempotencyGateway.Claim;
import com.moneylog.core.transaction.app.gateways.TransactionGateway;
import com.moneylog.core.transaction.domain.CardCashOutPlan;
import com.moneylog.core.transaction.domain.CardCashOutTransactions;
import com.moneylog.core.transaction.domain.CardInvoices;
import com.moneylog.core.transaction.domain.Transaction;
import com.moneylog.core.transaction.domain.TransactionHeader;
import com.moneylog.core.transaction.domain.TransactionOrigin;
import com.moneylog.core.transaction.exception.CardCashOutExceptions.CardCashOutInvalidException;

public class CreateCardCashOutUseCaseImpl implements CreateCardCashOutUseCase {

    private static final String DESCRIPTION = "Pix no crédito";

    private final CardGateway cards;
    private final AccountGateway accounts;
    private final CategoryGateway categories;
    private final PersonGateway persons;
    private final DebtGateway debts;
    private final TransactionGateway transactions;
    private final LedgerWriteGateway ledger;
    private final IdempotencyGateway idempotency;
    private final IdGenerator ids;
    private final TimeProvider time;

    @SuppressWarnings("java:S107")
    public CreateCardCashOutUseCaseImpl(CardGateway cards, AccountGateway accounts, CategoryGateway categories, PersonGateway persons,
                                        DebtGateway debts, TransactionGateway transactions, LedgerWriteGateway ledger,
                                        IdempotencyGateway idempotency, IdGenerator ids, TimeProvider time) {
        this.cards = cards;
        this.accounts = accounts;
        this.categories = categories;
        this.persons = persons;
        this.debts = debts;
        this.transactions = transactions;
        this.ledger = ledger;
        this.idempotency = idempotency;
        this.ids = ids;
        this.time = time;
    }

    @Override
    public DebtView handle(CreateCardCashOutCommand c) {
        UUID debtId = ids.next();
        if (c.idempotencyKey() != null) {
            Claim existing = IdempotentClaim.claimOrFind(idempotency, c.userId(), IDEMPOTENCY_SCOPE, c.idempotencyKey(), hashOf(c), debtId,
                    time.now());
            if (existing != null) {
                IdempotentClaim.requireSameCommand(existing, hashOf(c));
                return view(c.userId(), debts.findById(c.userId(), existing.resourceId())
                        .orElseThrow(() -> new DebtNotFoundException(existing.resourceId())));
            }
        }
        boolean forPerson = c.beneficiaryPersonId() != null;
        if (forPerson == (c.accountId() != null)) {
            throw new CardCashOutInvalidException(forPerson ? "com beneficiário, a conta não existe (o principal vira valor a receber)."
                    : "informe a conta que recebe o principal.");
        }
        CardCashOutPlan plan = CardCashOutPlan.of(c.principal(), c.installments(), c.totalWithInterest(), c.rateBps());
        cards.lockForUpdate(c.userId(), c.cardId());
        CreditCard card = cards.findById(c.userId(), c.cardId()).map(Persisted::value)
                .orElseThrow(() -> new CardNotFoundException(c.cardId()));
        Category interestCategory = interestCategory(c);
        InvoiceChain chain = CardInvoices.chainFor(card, transactions.findAllOfCard(c.userId(), card.id()));
        LocalDate today = time.today();
        TransactionHeader header = new TransactionHeader(ids.next(), c.userId(), today, DESCRIPTION, null, TransactionOrigin.manual());
        Transaction transaction;
        Debt debt;
        if (forPerson) {
            Person person = persons.findById(c.userId(), c.beneficiaryPersonId()).map(Versioned::value)
                    .orElseThrow(() -> new PersonNotFoundException(c.beneficiaryPersonId()));
            transaction = CardCashOutTransactions.toPerson(header, plan, c.firstInvoiceDate(), card, debtId, interestCategory, chain,
                    today);
            debt = Debt.cardCashOutForPerson(debtId, c.userId(), person, plan.principal(), today, DESCRIPTION, transaction.id(),
                    c.channel());
        } else {
            accounts.lockForUpdate(c.userId(), c.accountId());
            Account account = accounts.findById(c.userId(), c.accountId()).map(Persisted::value)
                    .orElseThrow(() -> new AccountNotFoundException(c.accountId()));
            transaction = CardCashOutTransactions.toAccount(header, plan, c.firstInvoiceDate(), card, account, interestCategory, chain,
                    today);
            debt = Debt.cardCashOutForMe(debtId, c.userId(), card.name(), plan.principal(), today, DESCRIPTION, transaction.id(),
                    c.channel());
        }
        Versioned<Debt> saved = debts.insert(debt);
        transactions.insert(transaction);
        ledger.append(c.userId(), transaction.entries());
        return view(c.userId(), saved);
    }

    private Category interestCategory(CreateCardCashOutCommand c) {
        if (c.interestCategoryId() != null) {
            return categories.findById(c.userId(), c.interestCategoryId())
                    .orElseThrow(() -> new CategoryNotFoundException(c.interestCategoryId()));
        }
        return categories.findBySystemKey(c.userId(), SystemCategories.DEFAULT_EXPENSE_KEY).filter(category -> !category.isArchived())
                .orElseThrow(() -> new DefaultCategoryUnavailableException(SystemCategories.DEFAULT_EXPENSE_KEY));
    }

    private DebtView view(UUID userId, Versioned<Debt> debt) {
        return DebtViews.one(persons, debts, categories, userId, debt, time.today());
    }

    private static String hashOf(CreateCardCashOutCommand c) {
        return IdempotentClaim.hash("card=" + c.cardId() + ";account=" + c.accountId() + ";principal=" + c.principal().toPlainString()
                + ";installments=" + c.installments() + ";total=" + (c.totalWithInterest() == null ? "-" : c.totalWithInterest().toPlainString())
                + ";rate=" + c.rateBps() + ";first=" + c.firstInvoiceDate() + ";beneficiary=" + c.beneficiaryPersonId()
                + ";category=" + c.interestCategoryId() + ";channel=" + c.channel() + ";");
    }
}
