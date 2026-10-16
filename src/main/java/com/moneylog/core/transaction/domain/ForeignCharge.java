package com.moneylog.core.transaction.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

import com.moneylog.core.sk.money.AmountLimit;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.transaction.exception.FxExceptions.FxInvalidException;
import com.moneylog.core.transaction.exception.FxExceptions.FxRateRequiredException;

public record ForeignCharge(Money original, BigDecimal fxRate, int iofBps) {

    public static final int DEFAULT_IOF_BPS = 350;
    public static final int MAX_IOF_BPS = 10000;
    public static final int FX_SCALE = 6;

    private static final BigDecimal BPS_BASE = BigDecimal.valueOf(10000);

    public ForeignCharge {
        if (original == null || !original.isPositive()) {
            throw new FxInvalidException("o valor original precisa ser maior que zero.");
        }
        if (CurrencyCode.BRL.equals(original.currency())) {
            throw new FxInvalidException("câmbio e IOF só existem com moeda diferente de BRL.");
        }
        if (fxRate == null) {
            throw new FxRateRequiredException();
        }
        if (fxRate.signum() <= 0 || fxRate.stripTrailingZeros().scale() > FX_SCALE) {
            throw new FxInvalidException("o câmbio precisa ser positivo, com até " + FX_SCALE + " casas.");
        }
        if (iofBps < 0 || iofBps > MAX_IOF_BPS) {
            throw new FxInvalidException("o IOF precisa estar entre 0 e " + MAX_IOF_BPS + " pontos-base.");
        }
        fxRate = fxRate.setScale(FX_SCALE, RoundingMode.UNNECESSARY);
    }

    public static ForeignCharge of(Money original, BigDecimal fxRate, Integer iofBps) {
        return new ForeignCharge(original, fxRate, iofBps == null ? DEFAULT_IOF_BPS : iofBps);
    }

    public static void rejectOrphans(Money original, BigDecimal fxRate, Integer iofBps) {
        if (original == null && (fxRate != null || iofBps != null)) {
            throw new FxInvalidException("fxRate e iofBps exigem originalAmount em moeda estrangeira.");
        }
    }

    public Money brl() {
        BigDecimal cents = BigDecimal.valueOf(original.cents()).multiply(fxRate)
                .multiply(BigDecimal.valueOf(10000L + iofBps)).divide(BPS_BASE, 0, RoundingMode.HALF_UP);
        return AmountLimit.ensureWithinLimit(Money.ofCents(cents.longValueExact(), CurrencyCode.BRL), "amount");
    }

    public ForeignCharge withRate(BigDecimal newRate, Integer newIofBps, Money newOriginal) {
        return ForeignCharge.of(newOriginal == null ? original : newOriginal, newRate, newIofBps == null ? iofBps : newIofBps);
    }
}
