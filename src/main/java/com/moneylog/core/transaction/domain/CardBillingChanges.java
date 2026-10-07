package com.moneylog.core.transaction.domain;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.moneylog.core.card.domain.BillingChange;
import com.moneylog.core.card.domain.CreditCard;
import com.moneylog.core.card.domain.TransactionInstallment;

/**
 * Mudança de fechamento/vencimento com a realocação das faturas {@code FUTURE} (D1 §2.5.1 regra 6, S-42, C1). Fica no
 * pacote de movimentação porque precisa das parcelas e dos créditos; a regra de realocação é a função pura de
 * {@link BillingChange}.
 *
 * <p>Tudo é calculado antes de mudar qualquer coisa: se algum item não puder ser realocado, nem o cartão nem as
 * movimentações mudam. O caso de uso grava o cartão (regras) e as movimentações devolvidas na mesma transação.
 */
public final class CardBillingChanges {

    private CardBillingChanges() {
    }

    /**
     * Troca os dias do cartão e realoca as parcelas e os créditos das faturas afetadas.
     *
     * @param transactions todas as movimentações do cartão (ativas e estornadas; as de outros cartões são ignoradas)
     * @return as movimentações que mudaram de fatura (para o gateway gravar)
     */
    public static List<Transaction> changeBillingDays(CreditCard card, Collection<Transaction> transactions, int newClosingDay,
                                                      int newDueDay, LocalDate today) {
        Objects.requireNonNull(card, "card");
        Objects.requireNonNull(transactions, "transactions");
        List<Transaction> ofCard = transactions.stream().filter(t -> card.id().equals(t.cardId())).toList();
        CardInvoices.chainFor(card, ofCard); // a cadeia atual precisa estar consistente antes da mudança

        BillingChange change = card.planBillingChange(newClosingDay, newDueDay, today);
        Map<Transaction, List<TransactionInstallment>> installments = new LinkedHashMap<>();
        Map<Transaction, LocalDate> credits = new LinkedHashMap<>();
        for (Transaction tx : ofCard) {
            switch (tx.type()) {
                case CARD_PURCHASE -> {
                    List<TransactionInstallment> reallocated = change.reallocateInstallments(tx.installments());
                    if (movedAny(tx.installments(), reallocated)) {
                        installments.put(tx, reallocated);
                    }
                }
                case REFUND -> {
                    LocalDate target = change.reallocateDated(tx.invoiceClosingDate(), tx.date());
                    if (!target.equals(tx.invoiceClosingDate())) {
                        credits.put(tx, target);
                    }
                }
                case INVOICE_PAYMENT -> change.keepPayment(tx.invoiceClosingDate());
                default -> { }
            }
        }

        card.applyBillingChange(change);
        installments.forEach(Transaction::reallocateInstallments);
        credits.forEach(Transaction::reallocateInvoice);
        CardInvoices.chainFor(card, ofCard); // e consistente depois dela

        List<Transaction> changed = new ArrayList<>(installments.keySet());
        credits.keySet().stream().filter(t -> !changed.contains(t)).forEach(changed::add);
        return List.copyOf(changed);
    }

    private static boolean movedAny(List<TransactionInstallment> before, List<TransactionInstallment> after) {
        Map<Object, LocalDate> old = new LinkedHashMap<>();
        before.forEach(i -> old.put(i.id(), i.invoiceClosingDate()));
        return after.stream().anyMatch(i -> !i.invoiceClosingDate().equals(old.get(i.id())));
    }
}
