package com.moneylog.core.sk.money;

import java.util.Objects;

import com.moneylog.core.sk.money.MoneyExceptions.AmountAboveLimitException;

/**
 * Teto de valor de entrada (S-78): R$ 999.999.999,99 ({@value #MAX_CENTS} centavos) por movimentação, para o limite do
 * cartão, o saldo inicial da conta (em módulo), o principal da dívida e o valor da recorrência.
 *
 * <p>É uma validação contra erro de digitação (regra transversal 5), não um limite técnico. Com ele, somar até ~92
 * milhões de lançamentos no teto ainda cabe em {@code long}, então nenhuma lente transborda na prática; o
 * {@link Money} continua lançando {@code MoneyOverflowException} se isso acontecer.
 */
public final class AmountLimit {

    /** R$ 999.999.999,99. */
    public static final long MAX_CENTS = 99_999_999_999L;

    private AmountLimit() {
    }

    public static Money max(CurrencyCode currency) {
        return Money.ofCents(MAX_CENTS, currency);
    }

    /**
     * Garante {@code |amount| ≤ teto}.
     *
     * @param field nome do campo, para a mensagem (ex.: {@code amount}, {@code limit}, {@code openingBalance})
     * @throws AmountAboveLimitException se o valor (em módulo) passar do teto
     */
    public static Money ensureWithinLimit(Money amount, String field) {
        Objects.requireNonNull(amount, "amount");
        if (amount.cents() > MAX_CENTS || amount.cents() < -MAX_CENTS) {
            throw new AmountAboveLimitException(field, max(amount.currency()));
        }
        return amount;
    }
}
