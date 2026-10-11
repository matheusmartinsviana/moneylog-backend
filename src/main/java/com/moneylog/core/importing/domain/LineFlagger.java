package com.moneylog.core.importing.domain;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;
import java.util.regex.Pattern;

public final class LineFlagger {

    private static final java.util.List<String> INVOICE_PAYMENT_MARKERS = java.util.List.of("PAGAMENTO RECEBIDO", "PAGTO FATURA",
            "PGTO FATURA", "PAGAMENTO FATURA", "PAGAMENTO DE FATURA", "PAGAMENTO EFETUADO", "PAGAMENTO ON LINE", "PAGAMENTO ONLINE",
            "PAGTO DEBITO AUTOMATICO");
    private static final Pattern INSTALLMENT_KEYWORD = Pattern.compile("(?i)\\bPARC(ELA|ELAS)?\\.?\\s*(\\d{1,2})\\s*(/|DE)\\s*(\\d{1,2})\\b");
    private static final Pattern INSTALLMENT_TRAILING = Pattern.compile("(?:^|\\s)(\\d{2})\\s*/\\s*(\\d{2})\\s*$");

    private LineFlagger() {
    }

    public static Set<ImportLineFlag> flags(ImportLine line, boolean cardBatch, boolean oppositeLegFound) {
        Set<ImportLineFlag> flags = EnumSet.noneOf(ImportLineFlag.class);
        if (cardBatch) {
            if (isInvoicePayment(line)) {
                flags.add(ImportLineFlag.LOOKS_LIKE_INVOICE_PAYMENT);
            } else if (line.direction() == ImportDirection.IN) {
                flags.add(ImportLineFlag.CARD_CREDIT_NOT_IMPORTED);
            } else if (looksLikeInstallment(line.description())) {
                flags.add(ImportLineFlag.LOOKS_LIKE_INSTALLMENT);
            }
        } else if (oppositeLegFound) {
            flags.add(ImportLineFlag.POSSIBLE_TRANSFER);
        }
        return flags;
    }

    static boolean isInvoicePayment(ImportLine line) {
        String text = line.normalizedDescription();
        return INVOICE_PAYMENT_MARKERS.stream().anyMatch(text::contains);
    }

    static boolean looksLikeInstallment(String description) {
        if (description == null) {
            return false;
        }
        var keyword = INSTALLMENT_KEYWORD.matcher(description);
        if (keyword.find()) {
            return valid(Integer.parseInt(keyword.group(2)), Integer.parseInt(keyword.group(4)));
        }
        var trailing = INSTALLMENT_TRAILING.matcher(description);
        return trailing.find() && valid(Integer.parseInt(trailing.group(1)), Integer.parseInt(trailing.group(2)));
    }

    private static boolean valid(int number, int total) {
        return total >= 2 && total <= 36 && number >= 1 && number <= total;
    }

    public static boolean requiresDecision(Collection<ImportLineFlag> flags) {
        return flags.stream().anyMatch(ImportLineFlag::requiresDecision);
    }
}
