package com.moneylog.core.finance.app.cards.usecase;

import java.util.UUID;

import com.moneylog.core.account.app.gateways.AccountGateway;
import com.moneylog.core.account.domain.Account;
import com.moneylog.core.account.domain.LiquidityClass;
import com.moneylog.core.account.exception.AccountExceptions.AccountNotFoundException;
import com.moneylog.core.sk.persistence.Persisted;
import com.moneylog.core.transaction.domain.TransactionType;
import com.moneylog.core.transaction.exception.TransactionExceptions.AccountNotCashException;

/** Regras da conta de pagamento padrão do cartão (a que o pagamento da fatura usa por padrão, S-123). */
final class CardAccounts {

    private CardAccounts() {
    }

    /**
     * A conta existe (de outro usuário é como se não existisse), está ativa e é {@code CASH}: o pagamento de fatura sai
     * de conta líquida (S-53).
     */
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
