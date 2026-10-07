package com.moneylog.core.finance.app.export;

import com.moneylog.core.sk.exception.BusinessRuleException;

/**
 * S-260 (M1 da revisão da Etapa 5): o arquivo teria mais movimentações que o teto documentado. {@code 422 EXPORT_TOO_LARGE}; nada é
 * escrito. O usuário reduz o período/filtro (CSV) ou o teto sobe por configuração (backup).
 */
public class ExportTooLargeException extends BusinessRuleException {

    public ExportTooLargeException(int count, int max) {
        super("EXPORT_TOO_LARGE", "A exportação teria " + count + " movimentações e o limite é " + max
                + ". Reduza o período ou o filtro (CSV) e tente de novo.");
    }
}
