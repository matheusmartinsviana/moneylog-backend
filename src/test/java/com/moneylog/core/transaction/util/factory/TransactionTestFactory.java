package com.moneylog.core.transaction.util.factory;

import static com.moneylog.core.util.Ids.USER_ID;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.moneylog.core.account.util.factory.AccountTestFactory;
import com.moneylog.core.card.domain.CreditCard;
import com.moneylog.core.card.domain.InvoiceChain;
import com.moneylog.core.category.domain.Category;
import com.moneylog.core.category.util.factory.CategoryTestFactory;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.transaction.domain.AccountTransactions;
import com.moneylog.core.transaction.domain.CardTransactions;
import com.moneylog.core.transaction.domain.PaymentMethod;
import com.moneylog.core.transaction.domain.Transaction;
import com.moneylog.core.transaction.domain.TransactionHeader;

public final class TransactionTestFactory {

    public static final UUID EXPENSE_ID = UUID.fromString("00000000-0000-0000-0000-0000000e0001");
    public static final UUID TX_ID = UUID.fromString("00000000-0000-0000-0000-0000000e0002");
    public static final UUID REFUND_ID = UUID.fromString("00000000-0000-0000-0000-0000000e0003");
    public static final LocalDate DATE = LocalDate.of(2026, 10, 10);
    public static final Instant NOW = Instant.parse("2026-10-10T15:00:00Z");

    private TransactionTestFactory() {
    }

    public static TransactionHeader umHeader(UUID id, LocalDate date) {
        return TransactionHeader.of(id, USER_ID, date, "Movimentação de teste");
    }

    public static TransactionHeader umHeader() {
        return umHeader(TX_ID, DATE);
    }

    public static Transaction compraNoCalendario(TransactionHeader header, Money amount, CreditCard card, Category category,
                                                 int installments) {
        return CardTransactions.purchase(header, amount, card, category, installments, InvoiceChain.empty(card), header.date());
    }

    public static Transaction umaDespesaPix() {
        return AccountTransactions.expense(umHeader(EXPENSE_ID, DATE), Money.of("100.00"),
                AccountTestFactory.umaContaCorrente(), CategoryTestFactory.umaCategoriaDeDespesa(), PaymentMethod.PIX);
    }
}
