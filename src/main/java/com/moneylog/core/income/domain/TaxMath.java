package com.moneylog.core.income.domain;

final class TaxMath {

    private TaxMath() {
    }

    static long divideHalfUp(long numerator, long denominator) {
        long quotient = numerator / denominator;
        long remainder = numerator % denominator;
        return Math.abs(remainder) * 2 >= denominator ? quotient + Long.signum(numerator) : quotient;
    }

    static long rateOf(long cents, int rateBps) {
        return divideHalfUp(Math.multiplyExact(cents, (long) rateBps), 10_000L);
    }
}
