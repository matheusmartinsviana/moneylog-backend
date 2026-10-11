package com.moneylog.core.finance.app.export;

import com.moneylog.core.sk.exception.BusinessRuleException;

public class ExportTooLargeException extends BusinessRuleException {

    public ExportTooLargeException(int count, int max) {
        super("EXPORT_TOO_LARGE", "A exportação teria " + count + " movimentações e o limite é " + max
                + ". Reduza o período ou o filtro (CSV) e tente de novo.");
    }
}
