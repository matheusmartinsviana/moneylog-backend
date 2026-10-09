package com.moneylog.core.finance.app.transactions;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import com.moneylog.core.finance.app.transactions.usecase.CreateTransactionCommand;
import com.moneylog.core.finance.app.transactions.usecase.CreateTransactionCommand.Adjustment;
import com.moneylog.core.finance.app.transactions.usecase.CreateTransactionCommand.CardPurchase;
import com.moneylog.core.finance.app.transactions.usecase.CreateTransactionCommand.DebtPayment;
import com.moneylog.core.finance.app.transactions.usecase.CreateTransactionCommand.LoanGiven;
import com.moneylog.core.finance.app.transactions.usecase.CreateTransactionCommand.MoneyReceived;
import com.moneylog.core.finance.app.transactions.usecase.CreateTransactionCommand.NewDebtSpec;
import com.moneylog.core.finance.app.transactions.usecase.CreateTransactionCommand.Share;
import com.moneylog.core.finance.app.transactions.usecase.CreateTransactionCommand.Common;
import com.moneylog.core.finance.app.transactions.usecase.CreateTransactionCommand.Expense;
import com.moneylog.core.finance.app.transactions.usecase.CreateTransactionCommand.Income;
import com.moneylog.core.finance.app.transactions.usecase.CreateTransactionCommand.Investment;
import com.moneylog.core.finance.app.transactions.usecase.CreateTransactionCommand.Refund;
import com.moneylog.core.finance.app.transactions.usecase.CreateTransactionCommand.Transfer;
import com.moneylog.core.finance.app.transactions.usecase.CreateTransactionCommand.Unavailable;
import com.moneylog.core.transaction.domain.AdjustmentKind;

/**
 * Hash (SHA-256 em hexadecimal) do <b>comando canônico</b> de criação (S-87): não depende dos bytes do corpo
 * (ordem das chaves, espaços) nem da forma do valor ({@code "100.0"} e {@code "100.00"} são o mesmo). Os campos entram
 * numa ordem fixa, o texto é normalizado como o domínio faz (sem espaços nas pontas, em branco = ausente) e o
 * {@code kind} ausente do ajuste vale {@code CORRECTION}. O dono não entra: a chave já é única por usuário.
 */
public final class CanonicalCommandHash {

    private CanonicalCommandHash() {
    }

    public static String of(CreateTransactionCommand command) {
        StringBuilder canonical = new StringBuilder();
        switch (command) {
            case Expense c -> {
                field(canonical, "type", "EXPENSE");
                common(canonical, c.common());
                field(canonical, "accountId", c.accountId());
                field(canonical, "categoryId", c.categoryId());
                field(canonical, "method", c.method());
                shares(canonical, c.shares());
            }
            case Income c -> {
                field(canonical, "type", "INCOME");
                common(canonical, c.common());
                field(canonical, "accountId", c.accountId());
                field(canonical, "categoryId", c.categoryId());
                field(canonical, "method", c.method());
                if (c.incomeCompetence() != null) {
                    // só entra quando informada: o hash das receitas sem competência é o de antes (chaves de 24 h seguem valendo)
                    field(canonical, "incomeCompetence", c.incomeCompetence());
                }
            }
            case Transfer c -> {
                field(canonical, "type", "TRANSFER");
                common(canonical, c.common());
                field(canonical, "fromAccountId", c.fromAccountId());
                field(canonical, "toAccountId", c.toAccountId());
            }
            case Investment c -> {
                field(canonical, "type", "INVESTMENT");
                common(canonical, c.common());
                field(canonical, "cashAccountId", c.cashAccountId());
                field(canonical, "investmentAccountId", c.investmentAccountId());
                field(canonical, "direction", c.direction());
            }
            case Adjustment c -> {
                field(canonical, "type", "ADJUSTMENT");
                common(canonical, c.common());
                field(canonical, "accountId", c.accountId());
                field(canonical, "direction", c.direction());
                field(canonical, "kind", c.kind() == null ? AdjustmentKind.CORRECTION : c.kind());
                field(canonical, "reason", text(c.reason()));
            }
            case Refund c -> {
                field(canonical, "type", "REFUND");
                common(canonical, c.common());
                field(canonical, "accountId", c.accountId());
                field(canonical, "categoryId", c.categoryId());
                field(canonical, "refundOfTransactionId", c.refundOfTransactionId());
                field(canonical, "cardId", c.cardId());
            }
            case CardPurchase c -> {
                field(canonical, "type", "CARD_PURCHASE");
                common(canonical, c.common());
                field(canonical, "cardId", c.cardId());
                field(canonical, "categoryId", c.categoryId());
                field(canonical, "installments", c.installments());
            }
            case LoanGiven c -> {
                field(canonical, "type", "LOAN_GIVEN");
                common(canonical, c.common());
                field(canonical, "accountId", c.accountId());
                newDebt(canonical, "newDebt", c.newDebt());
                field(canonical, "debtId", c.debtId());
            }
            case MoneyReceived c -> {
                field(canonical, "type", "MONEY_RECEIVED");
                common(canonical, c.common());
                field(canonical, "accountId", c.accountId());
                field(canonical, "debtId", c.debtId());
                newDebt(canonical, "newDebt", c.newDebt());
            }
            case DebtPayment c -> {
                field(canonical, "type", "DEBT_PAYMENT");
                common(canonical, c.common());
                field(canonical, "accountId", c.accountId());
                field(canonical, "debtId", c.debtId());
            }
            case Unavailable c -> field(canonical, "type", c.type());
        }
        return sha256(canonical.toString());
    }

    /** Hash do comando canônico de pagamento de fatura (T55): a fatura, a conta, o valor e a data (o dono não entra). */
    public static String ofInvoicePayment(java.util.UUID invoiceId, java.util.UUID accountId, com.moneylog.core.sk.money.Money amount,
                                          java.time.LocalDate date) {
        StringBuilder canonical = new StringBuilder();
        field(canonical, "type", com.moneylog.core.transaction.domain.TransactionType.INVOICE_PAYMENT);
        field(canonical, "invoiceId", invoiceId);
        field(canonical, "accountId", accountId);
        field(canonical, "amount", amount == null ? null : amount.toPlainString());
        field(canonical, "currency", amount == null ? null : amount.currency().value());
        field(canonical, "date", date);
        return sha256(canonical.toString());
    }

    /**
     * As partes entram em ordem, cada uma com valor e dívida nova. Sem partes (despesa comum) nada é acrescentado: o hash
     * das despesas sem divisão é o mesmo de antes (as chaves de idempotência de 24 h continuam valendo depois da atualização).
     */
    private static void shares(StringBuilder canonical, java.util.List<Share> shares) {
        if (shares.isEmpty()) {
            return;
        }
        field(canonical, "shares", shares.size());
        for (int i = 0; i < shares.size(); i++) {
            Share share = shares.get(i);
            field(canonical, "share" + i + ".amount", share.amount() == null ? null : share.amount().toPlainString());
            newDebt(canonical, "share" + i + ".newDebt", share.newDebt());
        }
    }

    private static void newDebt(StringBuilder canonical, String name, NewDebtSpec spec) {
        field(canonical, name, spec == null ? null : "yes");
        if (spec != null) {
            field(canonical, name + ".personId", spec.personId());
            field(canonical, name + ".expectedDate", spec.expectedDate());
            field(canonical, name + ".description", text(spec.description()));
        }
    }

    private static void common(StringBuilder canonical, Common common) {
        field(canonical, "amount", common.amount() == null ? null : common.amount().toPlainString());
        field(canonical, "currency", common.amount() == null ? null : common.amount().currency().value());
        field(canonical, "date", common.date());
        field(canonical, "description", text(common.description()));
        field(canonical, "notes", text(common.notes()));
    }

    private static String text(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    /** {@code nome=tamanho:valor;}, com o tamanho para o valor não poder forjar outro campo. */
    private static void field(StringBuilder canonical, String name, Object value) {
        String text = value == null ? null : value.toString();
        canonical.append(name).append('=');
        if (text == null) {
            canonical.append('-');
        } else {
            canonical.append(text.length()).append(':').append(text);
        }
        canonical.append(';');
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponível", e);
        }
    }
}
