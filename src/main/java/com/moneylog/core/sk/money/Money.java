package com.moneylog.core.sk.money;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

import com.moneylog.core.sk.money.MoneyExceptions.CurrencyMismatchException;
import com.moneylog.core.sk.money.MoneyExceptions.InvalidMoneyAllocationException;
import com.moneylog.core.sk.money.MoneyExceptions.InvalidMoneyAmountException;
import com.moneylog.core.sk.money.MoneyExceptions.MoneyOverflowException;

/**
 * Dinheiro em <b>centavos {@code long}</b> + moeda (D1 §2.1, ADR-002). Imutável.
 *
 * <ul>
 *   <li>Nunca usa {@code double}/{@code float} (verificado pelo {@code ArchitectureTest}).</li>
 *   <li>Rejeita valor com mais de 2 casas: não existe arredondamento silencioso.</li>
 *   <li>Soma, subtração e multiplicação usam {@code Math.*Exact}: overflow lança {@link MoneyOverflowException}.</li>
 *   <li>Operar moedas diferentes lança {@link CurrencyMismatchException}.</li>
 *   <li>Pode ser negativo (ex.: saldo inicial de cheque especial, lado crédito de um posting).</li>
 * </ul>
 */
public final class Money implements Comparable<Money> {

    /** Casas decimais: o valor é guardado em centavos. */
    public static final int SCALE = 2;

    private static final Pattern DECIMAL = Pattern.compile("-?\\d+(\\.\\d{1,2})?");

    private final long cents;
    private final CurrencyCode currency;

    private Money(long cents, CurrencyCode currency) {
        this.cents = cents;
        this.currency = Objects.requireNonNull(currency, "currency");
    }

    /** Valor em BRL a partir de string decimal com até 2 casas (ex.: {@code "1200.00"}, {@code "-0.5"}). */
    public static Money of(String amount) {
        return of(amount, CurrencyCode.BRL);
    }

    public static Money of(String amount, CurrencyCode currency) {
        if (amount == null || !DECIMAL.matcher(amount).matches()) {
            throw new InvalidMoneyAmountException(String.valueOf(amount));
        }
        try {
            return new Money(new BigDecimal(amount).movePointRight(SCALE).longValueExact(), currency);
        } catch (ArithmeticException e) {
            throw new MoneyOverflowException(e);
        }
    }

    public static Money ofCents(long cents, CurrencyCode currency) {
        return new Money(cents, currency);
    }

    public static Money zero(CurrencyCode currency) {
        return new Money(0L, currency);
    }

    /** Soma uma lista de valores na moeda dada (lista vazia = zero). */
    public static Money sum(Iterable<Money> values, CurrencyCode currency) {
        Money total = zero(currency);
        for (Money value : values) {
            total = total.add(value);
        }
        return total;
    }

    public long cents() {
        return cents;
    }

    public CurrencyCode currency() {
        return currency;
    }

    public Money add(Money other) {
        requireSameCurrency(other);
        return exact(() -> Math.addExact(cents, other.cents));
    }

    public Money subtract(Money other) {
        requireSameCurrency(other);
        return exact(() -> Math.subtractExact(cents, other.cents));
    }

    public Money negate() {
        return exact(() -> Math.negateExact(cents));
    }

    public Money abs() {
        return exact(() -> Math.absExact(cents));
    }

    public Money times(long factor) {
        return exact(() -> Math.multiplyExact(cents, factor));
    }

    /**
     * Divide o valor em {@code parts} partes cuja soma é <b>exatamente</b> o total. O resto (centavos que não dividem)
     * vai para a <b>primeira</b> parte (S-3): {@code 1000.00 / 3 = [333.34, 333.33, 333.33]}.
     *
     * @throws InvalidMoneyAllocationException se {@code parts < 1}, se o valor não for positivo ou se alguma parte
     *                                         ficaria menor que 1 centavo (ex.: {@code 0.05} em 6)
     */
    public List<Money> allocate(int parts) {
        if (parts < 1) {
            throw new InvalidMoneyAllocationException("o número de partes precisa ser ao menos 1 (recebido " + parts + ").");
        }
        if (cents <= 0) {
            throw new InvalidMoneyAllocationException("só valores positivos podem ser divididos (recebido " + this + ").");
        }
        if (cents < parts) {
            throw new InvalidMoneyAllocationException(
                    this + " em " + parts + " partes deixaria parte menor que 1 centavo.");
        }
        long base = cents / parts;
        long remainder = cents % parts;
        List<Money> result = new ArrayList<>(parts);
        result.add(new Money(base + remainder, currency));
        for (int i = 1; i < parts; i++) {
            result.add(new Money(base, currency));
        }
        return Collections.unmodifiableList(result);
    }

    public boolean isZero() {
        return cents == 0;
    }

    public boolean isPositive() {
        return cents > 0;
    }

    public boolean isNegative() {
        return cents < 0;
    }

    public boolean isGreaterThan(Money other) {
        return compareTo(other) > 0;
    }

    public boolean isLessThan(Money other) {
        return compareTo(other) < 0;
    }

    /** Compara valores da mesma moeda. Moedas diferentes lançam {@link CurrencyMismatchException}. */
    @Override
    public int compareTo(Money other) {
        requireSameCurrency(other);
        return Long.compare(cents, other.cents);
    }

    /** Representação decimal com 2 casas, usada na API (ex.: {@code "1200.00"}, {@code "-0.50"}). */
    public String toPlainString() {
        return BigDecimal.valueOf(cents, SCALE).toPlainString();
    }

    private void requireSameCurrency(Money other) {
        Objects.requireNonNull(other, "other");
        if (!currency.equals(other.currency)) {
            throw new CurrencyMismatchException(currency, other.currency);
        }
    }

    private Money exact(java.util.function.LongSupplier operation) {
        try {
            return new Money(operation.getAsLong(), currency);
        } catch (ArithmeticException e) {
            throw new MoneyOverflowException(e);
        }
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Money other && cents == other.cents && currency.equals(other.currency);
    }

    @Override
    public int hashCode() {
        return Objects.hash(cents, currency);
    }

    @Override
    public String toString() {
        return currency + " " + toPlainString();
    }
}
