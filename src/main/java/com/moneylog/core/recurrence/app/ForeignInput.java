package com.moneylog.core.recurrence.app;

import java.math.BigDecimal;

import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.transaction.domain.ForeignCharge;
import com.moneylog.core.transaction.exception.FxExceptions.FxInvalidException;
import com.moneylog.core.transaction.exception.FxExceptions.FxRateRequiredException;

public record ForeignInput(Money originalAmount, BigDecimal fxRate, Integer iofBps) {

    public static final ForeignInput NONE = new ForeignInput(null, null, null);

    public ForeignCharge toBasis() {
        ForeignCharge.rejectOrphans(originalAmount, fxRate, iofBps);
        if (originalAmount == null) {
            return null;
        }
        if (CurrencyCode.BRL.equals(originalAmount.currency())) {
            throw new FxInvalidException("câmbio e IOF só existem com moeda diferente de BRL.");
        }
        if (fxRate == null) {
            throw new FxRateRequiredException();
        }
        return ForeignCharge.of(originalAmount, fxRate, iofBps);
    }

    public String canonical() {
        return originalAmount == null ? "" : "fx=" + originalAmount.toPlainString() + originalAmount.currency() + "@" + fxRate + "/"
                + iofBps + ";";
    }
}
