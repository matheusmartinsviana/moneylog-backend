package com.moneylog.core.finance.app.transactions.handlers;

import java.util.List;
import java.util.UUID;

import com.moneylog.core.account.app.gateways.AccountGateway;
import com.moneylog.core.account.domain.Account;
import com.moneylog.core.account.exception.AccountExceptions.AccountNotFoundException;
import com.moneylog.core.category.app.gateways.CategoryGateway;
import com.moneylog.core.category.domain.Category;
import com.moneylog.core.category.exception.CategoryExceptions.CategoryNotFoundException;
import com.moneylog.core.debt.app.gateways.DebtGateway;
import com.moneylog.core.debt.app.gateways.PersonGateway;
import com.moneylog.core.debt.domain.Debt;
import com.moneylog.core.debt.domain.Person;
import com.moneylog.core.debt.exception.DebtExceptions.DebtNotFoundException;
import com.moneylog.core.debt.exception.DebtExceptions.PersonNotFoundException;
import com.moneylog.core.finance.app.transactions.usecase.CreateTransactionCommand.Common;
import com.moneylog.core.finance.app.transactions.usecase.CreateTransactionCommand.NewDebtSpec;
import com.moneylog.core.sk.id.IdGenerator;
import com.moneylog.core.sk.persistence.Versioned;
import com.moneylog.core.transaction.domain.NewDebt;
import com.moneylog.core.finance.app.transactions.usecase.CreateTransactionCommand.Refund;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.transaction.domain.RefundOrigin;
import com.moneylog.core.transaction.exception.TransactionExceptions.TransactionNotFoundException;
import com.moneylog.core.sk.persistence.Persisted;
import com.moneylog.core.transaction.app.gateways.TransactionGateway;
import com.moneylog.core.transaction.domain.Transaction;
import com.moneylog.core.transaction.domain.TransactionHeader;
import com.moneylog.core.transaction.domain.TransactionOrigin;

/** O que os handlers têm em comum: carregar conta e categoria do usuário e montar o cabeçalho. */
public class HandlerSupport {

    private final TransactionGateway transactions;
    private final AccountGateway accounts;
    private final CategoryGateway categories;
    private final PersonGateway persons;
    private final DebtGateway debts;
    private final IdGenerator ids;

    /** Só contas e categorias (Etapas 2 e 3): os handlers de dívida e a divisão de despesa não estão ligados. */
    public HandlerSupport(TransactionGateway transactions, AccountGateway accounts, CategoryGateway categories) {
        this(transactions, accounts, categories, null, null, null);
    }

    /** Com pessoas, dívidas e o gerador de ids (Etapa 4). */
    public HandlerSupport(TransactionGateway transactions, AccountGateway accounts, CategoryGateway categories, PersonGateway persons,
                          DebtGateway debts, IdGenerator ids) {
        this.transactions = transactions;
        this.accounts = accounts;
        this.categories = categories;
        this.persons = persons;
        this.debts = debts;
        this.ids = ids;
    }

    /** Os handlers de dívida só existem quando as portas de pessoa e dívida e o gerador de ids foram informados. */
    public boolean hasDebts() {
        return persons != null && debts != null && ids != null;
    }

    Person person(UUID userId, UUID personId) {
        return persons.findById(userId, personId).map(Versioned::value).orElseThrow(() -> new PersonNotFoundException(personId));
    }

    Debt debt(UUID userId, UUID debtId) {
        return debts.findById(userId, debtId).map(Versioned::value).orElseThrow(() -> new DebtNotFoundException(debtId));
    }

    /** A dívida que nasce com a movimentação: pessoa do usuário (outra → 404), previsão e descrição (sem ela, a da movimentação). */
    NewDebt newDebt(UUID userId, NewDebtSpec spec, TransactionHeader header) {
        return new NewDebt(ids.next(), person(userId, spec.personId()), spec.expectedDate(),
                spec.description() == null || spec.description().isBlank() ? header.description() : spec.description());
    }

    /** As dívidas cuja origem é a movimentação (a despesa dividida tem uma por parte); vazio se as dívidas não estão ligadas. */
    List<Debt> debtsOriginatedBy(UUID userId, UUID transactionId) {
        return debts == null ? List.of() : debts.findByOriginTransaction(userId, transactionId).stream().map(Versioned::value).toList();
    }

    IdGenerator ids() {
        return ids;
    }

    TransactionGateway transactions() {
        return transactions;
    }

    TransactionHeader header(Common common, UUID id, TransactionOrigin origin) {
        return new TransactionHeader(id, common.userId(), common.date(), common.description(), common.notes(), origin);
    }

    Account account(UUID userId, UUID accountId) {
        return accounts.findById(userId, accountId).map(Persisted::value).orElseThrow(() -> new AccountNotFoundException(accountId));
    }

    /**
     * Na edição, a categoria arquivada só é recusada se a edição a <b>troca</b> por ela (B9, S-113); mantendo a que a
     * movimentação já tinha, a edição passa.
     */
    Category category(UUID userId, UUID categoryId, Transaction editing) {
        Category category = categories.findById(userId, categoryId).orElseThrow(() -> new CategoryNotFoundException(categoryId));
        return editing != null && categoryId.equals(editing.categoryId()) ? category.forExistingUse() : category;
    }

    /**
     * A despesa ou compra de origem do reembolso e quanto dela já foi reembolsado, ou {@code null} sem origem. B1: trava a
     * movimentação original antes de ler a soma; reembolsos, edição e estorno dela se serializam aqui.
     */
    RefundOrigin refundOrigin(Refund refund, Transaction editing) {
        if (refund.refundOfTransactionId() == null) {
            return null;
        }
        transactions.lockForUpdate(refund.userId(), refund.refundOfTransactionId());
        Transaction original = transactions.findById(refund.userId(), refund.refundOfTransactionId()).map(Persisted::value)
                .orElseThrow(() -> new TransactionNotFoundException(refund.refundOfTransactionId()));
        Money alreadyRefunded = transactions.sumActiveRefundsOf(refund.userId(), original.id());
        if (editing != null && editing.isActive() && original.id().equals(editing.refundOfId())) {
            alreadyRefunded = alreadyRefunded.subtract(editing.amount());
        }
        // despesa dividida (S-137): a parte que outras pessoas devem não é consumo do usuário, então não pode ser "reembolsada" como
        // consumo (o consumo da categoria ficaria negativo): o teto do reembolso é a parte do próprio usuário
        for (Debt shared : debtsOriginatedBy(refund.userId(), original.id())) {
            alreadyRefunded = alreadyRefunded.add(shared.principal());
        }
        return new RefundOrigin(original, alreadyRefunded);
    }
}
