package com.moneylog.core.finance.app.transactions;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.moneylog.core.account.domain.Account;
import com.moneylog.core.card.domain.CreditCard;
import com.moneylog.core.card.domain.InstallmentStatus;
import com.moneylog.core.category.domain.Category;
import com.moneylog.core.debt.app.DebtSummary;
import com.moneylog.core.finance.app.cards.InvoiceView;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.time.Competence;
import com.moneylog.core.transaction.domain.Transaction;

/**
 * Movimentação com o que a API calcula (S-62, S-88, S-90): impacto nas quatro lentes e flags de ação. Contas e
 * categoria são as referenciadas (para devolver id e nome); {@code refundOfDescription} só existe em reembolso ligado.
 * {@code cardInfo} só existe nas movimentações de cartão (compra, reembolso no cartão, pagamento de fatura). {@code debts} traz
 * a dívida das movimentações de dívida (origem ou quitação) e as partes da despesa dividida (uma dívida por parte).
 */
public record TransactionDetails(Transaction transaction, Instant createdAt, Instant updatedAt, Account account,
                                 Account counterpartAccount, Category category, String refundOfDescription, Impact impact,
                                 Flags flags, CardInfo cardInfo, List<DebtSummary> debts) {

    public TransactionDetails {
        Objects.requireNonNull(transaction, "transaction");
        Objects.requireNonNull(impact, "impact");
        Objects.requireNonNull(flags, "flags");
        debts = debts == null ? List.of() : List.copyOf(debts);
    }

    /** Movimentação sem dívidas. */
    public TransactionDetails(Transaction transaction, Instant createdAt, Instant updatedAt, Account account,
                              Account counterpartAccount, Category category, String refundOfDescription, Impact impact,
                              Flags flags, CardInfo cardInfo) {
        this(transaction, createdAt, updatedAt, account, counterpartAccount, category, refundOfDescription, impact, flags, cardInfo,
                List.of());
    }

    /** Movimentação sem cartão (tipos em conta). */
    public TransactionDetails(Transaction transaction, Instant createdAt, Instant updatedAt, Account account,
                              Account counterpartAccount, Category category, String refundOfDescription, Impact impact,
                              Flags flags) {
        this(transaction, createdAt, updatedAt, account, counterpartAccount, category, refundOfDescription, impact, flags, null,
                List.of());
    }

    public record ConsumptionImpact(Competence competence, Money amount) {
    }

    /**
     * Efeito nas lentes (D1 §1). {@code commitment} é o compromisso que a própria movimentação cria: na compra no cartão,
     * Σ das parcelas não canceladas em faturas {@code FUTURE} (a parcela 1 já está na fatura corrente, que é o
     * compromisso do {@code /upcoming}); zero nos demais tipos.
     */
    public record Impact(Money cash, List<ConsumptionImpact> consumption, Money netWorth, Money commitment) {
    }

    public record Flags(boolean editable, boolean voidable, VoidBlockedBy voidBlockedBy) {
    }

    /** Parcela como a API mostra: o status é derivado da fatura (D1 §2.5.2). */
    public record InstallmentView(int number, int total, Money amount, Competence competence, UUID invoiceId,
                                  java.time.YearMonth invoiceReferenceMonth, LocalDate invoiceDueDate, InstallmentStatus status,
                                  boolean lateEntry) {
    }

    /**
     * O cartão da movimentação, as parcelas (compra) e a fatura (reembolso no cartão e pagamento de fatura).
     *
     * @param invoice fatura do reembolso/pagamento; {@code null} na compra
     */
    public record CardInfo(CreditCard card, List<InstallmentView> installments, InvoiceView invoice) {
        public CardInfo {
            installments = installments == null ? List.of() : List.copyOf(installments);
        }
    }
}
