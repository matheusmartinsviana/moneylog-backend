package com.moneylog.core.finance.app.export;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.moneylog.core.transaction.domain.AdjustmentDirection;
import com.moneylog.core.transaction.domain.InvestmentDirection;
import com.moneylog.core.transaction.domain.PaymentMethod;
import com.moneylog.core.transaction.domain.Transaction;
import com.moneylog.core.transaction.domain.TransactionStatus;
import com.moneylog.core.transaction.domain.TransactionType;

final class CsvHeaders {

    private static final List<String> PT = List.of("Data", "Tipo", "Descrição", "Categoria", "Conta/Cartão", "Método", "Valor", "Status", "Direção", "Competência da renda");
    private static final List<String> EN = List.of("Date", "Type", "Description", "Category", "Account/Card", "Method", "Amount", "Status", "Direction", "Income month");

    private static final Map<TransactionType, String> TYPE_PT = Map.ofEntries(Map.entry(TransactionType.EXPENSE, "Despesa"),
            Map.entry(TransactionType.INCOME, "Receita"), Map.entry(TransactionType.TRANSFER, "Transferência"),
            Map.entry(TransactionType.INVESTMENT, "Investimento"), Map.entry(TransactionType.LOAN_GIVEN, "Empréstimo concedido"),
            Map.entry(TransactionType.MONEY_RECEIVED, "Dinheiro recebido"), Map.entry(TransactionType.DEBT_PAYMENT, "Pagamento de dívida"),
            Map.entry(TransactionType.CARD_PURCHASE, "Compra no cartão"), Map.entry(TransactionType.INVOICE_PAYMENT, "Pagamento de fatura"),
            Map.entry(TransactionType.REFUND, "Reembolso"), Map.entry(TransactionType.ADJUSTMENT, "Ajuste"));
    private static final Map<TransactionType, String> TYPE_EN = Map.ofEntries(Map.entry(TransactionType.EXPENSE, "Expense"),
            Map.entry(TransactionType.INCOME, "Income"), Map.entry(TransactionType.TRANSFER, "Transfer"),
            Map.entry(TransactionType.INVESTMENT, "Investment"), Map.entry(TransactionType.LOAN_GIVEN, "Loan given"),
            Map.entry(TransactionType.MONEY_RECEIVED, "Money received"), Map.entry(TransactionType.DEBT_PAYMENT, "Debt payment"),
            Map.entry(TransactionType.CARD_PURCHASE, "Card purchase"), Map.entry(TransactionType.INVOICE_PAYMENT, "Invoice payment"),
            Map.entry(TransactionType.REFUND, "Refund"), Map.entry(TransactionType.ADJUSTMENT, "Adjustment"));
    private static final Map<PaymentMethod, String> METHOD_PT = Map.of(PaymentMethod.CASH, "Dinheiro", PaymentMethod.DEBIT, "Débito",
            PaymentMethod.PIX, "Pix", PaymentMethod.TRANSFER, "Transferência (TED/DOC)", PaymentMethod.BOLETO, "Boleto", PaymentMethod.OTHER, "Outro");
    private static final Map<PaymentMethod, String> METHOD_EN = Map.of(PaymentMethod.CASH, "Cash", PaymentMethod.DEBIT, "Debit",
            PaymentMethod.PIX, "Pix", PaymentMethod.TRANSFER, "Transfer (wire)", PaymentMethod.BOLETO, "Boleto", PaymentMethod.OTHER, "Other");
    private static final Map<TransactionStatus, String> STATUS_PT = Map.of(TransactionStatus.ACTIVE, "Ativa", TransactionStatus.VOIDED, "Estornada");
    private static final Map<TransactionStatus, String> STATUS_EN = Map.of(TransactionStatus.ACTIVE, "Active", TransactionStatus.VOIDED, "Voided");

    private final boolean english;

    private CsvHeaders(boolean english) {
        this.english = english;
    }

    static CsvHeaders of(Locale locale) {
        return new CsvHeaders(locale != null && "en".equalsIgnoreCase(locale.getLanguage()));
    }

    List<String> header() {
        return english ? EN : PT;
    }

    String type(TransactionType type) {
        return (english ? TYPE_EN : TYPE_PT).get(type);
    }

    String method(PaymentMethod method) {
        return method == null ? "" : (english ? METHOD_EN : METHOD_PT).get(method);
    }

    String status(TransactionStatus status) {
        return (english ? STATUS_EN : STATUS_PT).get(status);
    }

    String direction(Transaction t) {
        InvestmentDirection investment = t.investmentDirection();
        if (t.type() == TransactionType.INVESTMENT && investment != null) {
            return investment == InvestmentDirection.CONTRIBUTION ? (english ? "Contribution" : "Aplicação") : (english ? "Redemption" : "Resgate");
        }
        AdjustmentDirection adjustment = t.adjustmentDirection();
        if (t.type() == TransactionType.ADJUSTMENT && adjustment != null) {
            return adjustment == AdjustmentDirection.INCREASE ? (english ? "Credit" : "Crédito") : (english ? "Debit" : "Débito");
        }
        return "";
    }
}
