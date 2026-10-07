package com.moneylog.core.finance.app.transactions;

import java.time.LocalDate;
import java.util.Collection;

import com.moneylog.core.account.domain.Account;
import com.moneylog.core.card.domain.CardVoidRules;
import com.moneylog.core.card.domain.InvoiceChain;
import com.moneylog.core.debt.domain.Debt;
import com.moneylog.core.debt.domain.DebtVoidRules;
import com.moneylog.core.sk.ledger.VoidDecision;
import com.moneylog.core.finance.app.transactions.TransactionDetails.Flags;
import com.moneylog.core.transaction.domain.Transaction;
import com.moneylog.core.transaction.domain.TransactionType;
import com.moneylog.core.transaction.domain.TransactionVoidPolicy;

/**
 * Flags de ação (S-62, S-88, S-89): combinam a policy de estorno com o estado das contas e dos reembolsos, para o front
 * só habilitar ou não a ação (a recusa continua no backend). Conta arquivada: nem editar nem estornar
 * ({@code ACCOUNT_ARCHIVED}); despesa com reembolso ativo: não estorna ({@code HAS_ACTIVE_REFUNDS}).
 */
public final class TransactionFlags {

    private TransactionFlags() {
    }

    public static Flags of(Transaction transaction, Collection<Account> touchedAccounts, boolean hasActiveRefunds) {
        if (!transaction.isActive()) {
            return new Flags(false, false, null);
        }
        if (transaction.cardId() != null) {
            // sem a cadeia de faturas não há como decidir a guarda de fatura fechada: fecha (nem edita nem estorna)
            return transaction.type() == TransactionType.INVOICE_PAYMENT ? invoicePayment(touchedAccounts) : new Flags(false, false, null);
        }
        boolean accountTypes = TransactionVoidPolicy.ACCOUNT_ONLY_TYPES.contains(transaction.type());
        boolean archived = touchedAccounts.stream().anyMatch(account -> !account.isActive());
        if (archived) {
            return new Flags(false, false, VoidBlockedBy.ACCOUNT_ARCHIVED);
        }
        if (hasActiveRefunds) {
            return new Flags(accountTypes, false, VoidBlockedBy.HAS_ACTIVE_REFUNDS);
        }
        return new Flags(accountTypes, true, null);
    }

    /**
     * Flags das movimentações de dívida (S-36, S-141): a origem ({@code LOAN_GIVEN}, captação) só se estorna sem quitação ativa e
     * sem perdão; a quitação, sem perdão. Não são editáveis (estorne e lance de novo). Conta arquivada: nem estornar.
     */
    public static Flags ofDebt(Transaction transaction, Collection<Account> touchedAccounts, Debt debt) {
        if (!transaction.isActive()) {
            return new Flags(false, false, null);
        }
        if (touchedAccounts.stream().anyMatch(account -> !account.isActive())) {
            return new Flags(false, false, VoidBlockedBy.ACCOUNT_ARCHIVED);
        }
        VoidDecision decision = transaction.id().equals(debt.originTransactionId()) ? DebtVoidRules.forOrigin(debt)
                : DebtVoidRules.forSettlement(debt);
        return decision.allowed() ? new Flags(false, true, null) : new Flags(false, false, VoidBlockedBy.valueOf(decision.code()));
    }

    /**
     * Despesa dividida: as flags da despesa em conta ({@link #of}) e, se ela é estornável, as guardas das dívidas que ela originou
     * (nenhuma com quitação ativa nem perdoada). A edição (descrição, categoria, observação) segue permitida.
     */
    public static Flags ofShares(Transaction transaction, Collection<Account> touchedAccounts, boolean hasActiveRefunds,
                                 Collection<Debt> debts) {
        Flags base = of(transaction, touchedAccounts, hasActiveRefunds);
        if (!base.voidable()) {
            return base;
        }
        for (Debt debt : debts) {
            VoidDecision decision = DebtVoidRules.forOrigin(debt);
            if (!decision.allowed()) {
                return new Flags(base.editable(), false, VoidBlockedBy.valueOf(decision.code()));
            }
        }
        return base;
    }

    /**
     * Flags das movimentações de cartão (S-121, S-122), com a cadeia de faturas do cartão e o "hoje":
     * <ul>
     *   <li>compra: editável (só descrição, categoria e observação); estornável se nenhuma parcela não cancelada está em
     *       fatura fechada ({@code TRANSACTION_IN_CLOSED_INVOICE}) e não há reembolso ativo ({@code HAS_ACTIVE_REFUNDS});</li>
     *   <li>crédito no cartão: não editável; estornável só em fatura {@code FUTURE}/{@code OPEN};</li>
     *   <li>pagamento de fatura: não editável; sempre estornável, exceto com a conta arquivada.</li>
     * </ul>
     */
    public static Flags ofCard(Transaction transaction, Collection<Account> touchedAccounts, boolean hasActiveRefunds,
                               InvoiceChain chain, LocalDate today) {
        if (!transaction.isActive()) {
            return new Flags(false, false, null);
        }
        return switch (transaction.type()) {
            case INVOICE_PAYMENT -> invoicePayment(touchedAccounts);
            case CARD_PURCHASE -> {
                if (!CardVoidRules.forPurchase(transaction.installments(), chain, today).allowed()) {
                    yield new Flags(true, false, VoidBlockedBy.TRANSACTION_IN_CLOSED_INVOICE);
                }
                yield hasActiveRefunds ? new Flags(true, false, VoidBlockedBy.HAS_ACTIVE_REFUNDS) : new Flags(true, true, null);
            }
            case REFUND -> CardVoidRules.forCardRefund(transaction.invoiceClosingDate(), chain, today).allowed()
                    ? new Flags(false, true, null) : new Flags(false, false, VoidBlockedBy.TRANSACTION_IN_CLOSED_INVOICE);
            default -> new Flags(false, false, null);
        };
    }

    /** Sempre estornável (o status da fatura é recalculado, S-122), exceto com a conta arquivada (S-73). */
    private static Flags invoicePayment(Collection<Account> touchedAccounts) {
        boolean archivedAccount = touchedAccounts.stream().anyMatch(account -> !account.isActive());
        return archivedAccount ? new Flags(false, false, VoidBlockedBy.ACCOUNT_ARCHIVED) : new Flags(false, true, null);
    }
}
