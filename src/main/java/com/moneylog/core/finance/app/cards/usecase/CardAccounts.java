package com.moneylog.core.finance.app.cards.usecase;

import java.util.UUID;

import com.moneylog.core.account.app.gateways.AccountGateway;
import com.moneylog.core.account.domain.Account;
import com.moneylog.core.account.domain.LiquidityClass;
import com.moneylog.core.account.exception.AccountExceptions.AccountNotFoundException;
import com.moneylog.core.sk.persistence.Persisted;
import com.moneylog.core.transaction.domain.TransactionType;
import com.moneylog.core.transaction.exception.TransactionExceptions.AccountNotCashException;

final class CardAccounts {

    private CardAccounts() {
    }

    static void requireUsablePaymentAccount(AccountGateway accounts, UUID userId, UUID accountId) {
        if (accountId == null) {
            return;
        }
        Account account = accounts.findById(userId, accountId).map(Persisted::value)
                .orElseThrow(() -> new AccountNotFoundException(accountId));
        account.ensureCanReceiveTransactions();
        if (account.liquidity() != LiquidityClass.CASH) {
            throw new AccountNotCashException(TransactionType.INVOICE_PAYMENT);
        }
    }
}
