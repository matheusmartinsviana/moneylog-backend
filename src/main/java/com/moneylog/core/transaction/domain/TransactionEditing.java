package com.moneylog.core.transaction.domain;

import java.util.Objects;

import com.moneylog.core.sk.money.Money;
import com.moneylog.core.transaction.exception.TransactionExceptions.RefundCategoryLockedException;
import com.moneylog.core.transaction.exception.TransactionExceptions.RefundExceedsOriginalException;
import com.moneylog.core.transaction.exception.TransactionExceptions.TransactionNotEditableException;

/**
 * Edição de movimentação (D1 §2.4 regra 2, S-68, S-81): a movimentação editada é uma <b>substituta</b> montada pelas
 * mesmas fábricas da criação ({@link AccountTransactions}), com o mesmo id, dono, tipo e origem. Aqui ficam as
 * regras que só a edição tem:
 *
 * <ul>
 *   <li>só movimentação ativa e do mesmo {@code type} (senão {@code TRANSACTION_NOT_EDITABLE});</li>
 *   <li>despesa com reembolsos ativos: não troca de categoria ({@code REFUND_CATEGORY_LOCKED}) e o novo valor não fica
 *       abaixo da soma deles ({@code REFUND_EXCEEDS_ORIGINAL}), para o consumo da categoria nunca ficar negativo
 *       (S-9, caso 9 do PR 01);</li>
 *   <li>a edição de um {@code REFUND} revalida a despesa de origem por {@code validateRefundOrigin} <b>sem</b> contar o
 *       próprio reembolso: isso é feito ao montar a substituta ({@code AccountTransactionFactory}), com o teto = valor
 *       da original − os <b>outros</b> reembolsos.</li>
 * </ul>
 */
public final class TransactionEditing {

    private TransactionEditing() {
    }

    /**
     * Valida a substituição e devolve a substituta já com a versão da atual (o lock otimista é da persistência).
     *
     * @param activeRefunds Σ dos reembolsos ativos ligados à {@code current} (só importa se ela é uma despesa)
     */
    public static Transaction replace(Transaction current, Transaction replacement, Money activeRefunds) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(replacement, "replacement");
        if (!current.isActive()) {
            throw new TransactionNotEditableException("ela está estornada.");
        }
        if (!current.id().equals(replacement.id()) || !current.userId().equals(replacement.userId())
                || current.type() != replacement.type()) {
            throw new TransactionNotEditableException("o tipo, o id e o dono não mudam na edição.");
        }
        if (current.type() == TransactionType.EXPENSE && activeRefunds != null && activeRefunds.isPositive()) {
            if (!Objects.equals(current.categoryId(), replacement.categoryId())) {
                throw new RefundCategoryLockedException();
            }
            if (replacement.amount().isLessThan(activeRefunds)) {
                throw new RefundExceedsOriginalException();
            }
        }
        replacement.inheritVersion(current);
        return replacement;
    }
}
