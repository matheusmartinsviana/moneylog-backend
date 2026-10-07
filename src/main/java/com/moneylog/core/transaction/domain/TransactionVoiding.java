package com.moneylog.core.transaction.domain;

import java.util.Objects;
import java.util.UUID;

import com.moneylog.core.card.domain.InvoiceChain;
import com.moneylog.core.debt.domain.Debt;
import com.moneylog.core.sk.ledger.VoidDecision;
import com.moneylog.core.transaction.exception.TransactionExceptions.TransactionVoidRefusedException;
import com.moneylog.core.transaction.exception.TransactionExceptions.VoidContextMissingException;

/**
 * Ponto único de estorno de movimentação (D1 §2.4 regra 1, S-36, M1). Escolhe a guarda pelo tipo da movimentação e
 * aplica os efeitos colaterais:
 *
 * <ul>
 *   <li>movimentação de dívida ({@code debtId} preenchido): {@link DebtTransactionVoidPolicy}; a dívida é validada
 *       <b>antes</b> de a movimentação mudar e depois recebe o efeito (origem → dívida {@code VOIDED}; quitação → status
 *       recalculado);</li>
 *   <li>movimentação de cartão ({@code cardId} preenchido): {@link CardTransactionVoidPolicy} com a cadeia do mesmo
 *       cartão;</li>
 *   <li>despesa dividida ({@code shareDebts} preenchido): as guardas da despesa e de cada dívida de origem, todas antes de
 *       mudar qualquer coisa; as dívidas viram {@code VOIDED} junto;</li>
 *   <li>demais (em conta): {@link TransactionVoidPolicy#accountTransactions(java.util.Collection, com.moneylog.core.sk.money.Money)},
 *       que também recusa conta arquivada (S-73) e despesa com reembolso ativo (S-89).</li>
 * </ul>
 * {@code Transaction.voidTransaction} é package-private: fora deste pacote não há outro caminho de estorno.
 */
public final class TransactionVoiding {

    private TransactionVoiding() {
    }

    /**
     * Estorna a movimentação. Se a guarda (ou a dívida) recusar, nada muda: nem a movimentação nem a dívida.
     *
     * @throws VoidContextMissingException se faltar o cartão/cadeia ou a dívida que o tipo exige
     */
    public static void voidTransaction(Transaction transaction, VoidContext context) {
        Objects.requireNonNull(transaction, "transaction");
        Objects.requireNonNull(context, "context");
        if (transaction.debtId() != null) {
            voidDebtTransaction(transaction, context);
        } else if (transaction.cardId() != null) {
            if (context.card() == null || context.chain() == null) {
                throw new VoidContextMissingException(transaction.type(), "o cartão e a cadeia de faturas");
            }
            InvoiceChain chain = context.chain();
            transaction.voidTransaction(context.reason(), context.now(),
                    new CardTransactionVoidPolicy(context.card(), chain, context.today(), context.activeRefunds()));
        } else if (!context.shareDebts().isEmpty()) {
            voidSharedExpense(transaction, context);
        } else {
            requireAccountContext(transaction, context);
            transaction.voidTransaction(context.reason(), context.now(),
                    TransactionVoidPolicy.accountTransactions(context.accounts(), context.activeRefunds()));
        }
    }

    /**
     * Despesa dividida (D1 §2.6.1): é a origem de uma dívida por parte e segue as guardas delas. Todas as guardas (da despesa e
     * de cada dívida) são consultadas <b>antes</b> de qualquer mudança; recusando uma, nada muda. Aceito: a despesa vira
     * {@code VOIDED} (os lançamentos dela, inclusive os dos recebíveis, ficam inativos) e as dívidas viram {@code VOIDED}.
     */
    private static void voidSharedExpense(Transaction transaction, VoidContext context) {
        requireAccountContext(transaction, context);
        String validReason = transaction.prepareVoid(context.reason(), context.now(),
                TransactionVoidPolicy.accountTransactions(context.accounts(), context.activeRefunds()));
        for (Debt debt : context.shareDebts()) {
            debt.ensureCanVoidTransaction(transaction.id());
        }
        transaction.applyVoid(validReason, context.now());
        for (Debt debt : context.shareDebts()) {
            debt.markOriginVoided(transaction.id(), validReason, context.today());
        }
    }

    /** Falha fechado (B3): sem as contas tocadas e, para despesa, sem a soma dos reembolsos, não há como decidir. */
    private static void requireAccountContext(Transaction transaction, VoidContext context) {
        for (UUID accountId : new UUID[] {transaction.accountId(), transaction.counterpartAccountId()}) {
            if (accountId != null && context.accounts().stream().noneMatch(account -> accountId.equals(account.id()))) {
                throw new VoidContextMissingException(transaction.type(), "a conta " + accountId + " (withAccounts)");
            }
        }
        if (transaction.type() == TransactionType.EXPENSE && context.activeRefunds() == null) {
            throw new VoidContextMissingException(transaction.type(), "a soma dos reembolsos ativos (withActiveRefunds)");
        }
    }

    private static void voidDebtTransaction(Transaction transaction, VoidContext context) {
        Debt debt = context.debt();
        if (debt == null) {
            throw new VoidContextMissingException(transaction.type(), "a dívida");
        }
        if (context.accounts().stream().anyMatch(account -> !account.isActive())) {
            // S-73: estornar mudaria o saldo de uma conta arquivada (a conta só arquiva zerada); o caminho é desarquivar
            throw new TransactionVoidRefusedException(VoidDecision.refuse(TransactionVoidPolicy.ACCOUNT_ARCHIVED,
                    "a movimentação toca uma conta arquivada; desarquive a conta para estornar."));
        }
        String validReason = transaction.prepareVoid(context.reason(), context.now(), new DebtTransactionVoidPolicy(debt));
        debt.ensureCanVoidTransaction(transaction.id());
        transaction.applyVoid(validReason, context.now());
        if (transaction.id().equals(debt.originTransactionId())) {
            debt.markOriginVoided(transaction.id(), validReason, context.today());
        } else {
            debt.voidSettlement(transaction.id());
        }
    }
}
