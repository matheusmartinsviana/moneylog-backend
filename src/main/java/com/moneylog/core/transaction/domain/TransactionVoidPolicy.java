package com.moneylog.core.transaction.domain;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;

import com.moneylog.core.account.domain.Account;
import com.moneylog.core.sk.ledger.VoidDecision;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.transaction.exception.TransactionExceptions.VoidPolicyNotApplicableException;

/**
 * Porta da guarda de estorno (D1 §2.4, regra 1, S-36). O agregado consulta a policy antes de estornar; quem
 * conhece as faturas e as dívidas monta a policy certa (cartão: {@code CardTransactionVoidPolicy}; dívida:
 * {@code DebtTransactionVoidPolicy}). A policy só decide; ela não altera nada. Quem escolhe a policy pelo tipo é
 * {@link TransactionVoiding}, o único caminho de estorno fora do pacote (M1).
 */
@FunctionalInterface
public interface TransactionVoidPolicy {

    VoidDecision evaluate(Transaction transaction);

    /** Tipos que só envolvem contas, sempre estornáveis (D1 §2.4, regra 1, primeira linha). */
    Set<TransactionType> ACCOUNT_ONLY_TYPES = EnumSet.of(TransactionType.EXPENSE, TransactionType.INCOME,
            TransactionType.TRANSFER, TransactionType.INVESTMENT, TransactionType.ADJUSTMENT, TransactionType.REFUND);

    /**
     * Policy padrão dos tipos que só envolvem contas: sempre permite. Usada com um tipo que tem guarda própria
     * (cartão, dívida), lança {@link VoidPolicyNotApplicableException}, para um erro de ligação não virar estorno
     * sem guarda.
     */
    /** Recusa de estorno por conta arquivada (S-73, S-88); é o mesmo {@code code} da exceção da conta. */
    String ACCOUNT_ARCHIVED = "ACCOUNT_ARCHIVED";

    /** Recusa de estorno da despesa com reembolso ativo (S-89). */
    String HAS_ACTIVE_REFUNDS = "TRANSACTION_HAS_ACTIVE_REFUNDS";

    /**
     * A policy dos tipos em conta com o estado que a movimentação não carrega (S-73, S-88, S-89): conta arquivada →
     * {@code ACCOUNT_ARCHIVED}; despesa com reembolso ativo → {@code TRANSACTION_HAS_ACTIVE_REFUNDS}; senão permite.
     *
     * @param touchedAccounts contas que a movimentação toca
     * @param activeRefunds   Σ dos reembolsos ativos ligados a ela ({@code null} = nenhum)
     */
    static TransactionVoidPolicy accountTransactions(Collection<Account> touchedAccounts, Money activeRefunds) {
        TransactionVoidPolicy base = accountTransactions();
        return transaction -> {
            VoidDecision decision = base.evaluate(transaction);
            if (!decision.allowed()) {
                return decision;
            }
            if (touchedAccounts.stream().anyMatch(account -> !account.isActive())) {
                return VoidDecision.refuse(ACCOUNT_ARCHIVED,
                        "a movimentação toca uma conta arquivada; desarquive a conta para estornar.");
            }
            if (activeRefunds != null && activeRefunds.isPositive()) {
                return VoidDecision.refuse(HAS_ACTIVE_REFUNDS, "a despesa tem reembolsos ativos; estorne os reembolsos antes.");
            }
            return VoidDecision.allow();
        };
    }

    static TransactionVoidPolicy accountTransactions() {
        return transaction -> {
            boolean accountOnly = ACCOUNT_ONLY_TYPES.contains(transaction.type()) && transaction.cardId() == null;
            if (!accountOnly) {
                throw new VoidPolicyNotApplicableException(transaction.type());
            }
            return VoidDecision.allow();
        };
    }
}
