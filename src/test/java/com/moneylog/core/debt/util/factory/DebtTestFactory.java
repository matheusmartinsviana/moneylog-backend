package com.moneylog.core.debt.util.factory;

import static com.moneylog.core.util.Ids.USER_ID;

import java.time.LocalDate;
import java.util.UUID;

import com.moneylog.core.account.util.factory.AccountTestFactory;
import com.moneylog.core.debt.domain.Debt;
import com.moneylog.core.debt.domain.DebtDirection;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.transaction.domain.DebtOperation;
import com.moneylog.core.transaction.domain.DebtTransactions;
import com.moneylog.core.transaction.domain.NewDebt;
import com.moneylog.core.transaction.domain.TransactionHeader;

public final class DebtTestFactory {

    public static final UUID LOAN_DEBT_ID = UUID.fromString("00000000-0000-0000-0000-0000000d0b01");
    public static final UUID PAYABLE_DEBT_ID = UUID.fromString("00000000-0000-0000-0000-0000000d0b02");
    public static final UUID LOAN_TX_ID = UUID.fromString("00000000-0000-0000-0000-0000000e0b01");
    public static final UUID SETTLEMENT_TX_ID = UUID.fromString("00000000-0000-0000-0000-0000000e0b02");
    public static final LocalDate DATE = LocalDate.of(2026, 10, 10);
    public static final LocalDate EXPECTED = LocalDate.of(2026, 11, 5);

    private DebtTestFactory() {
    }

    public static DebtOperation umEmprestimoAoJoao() {
        return DebtTransactions.loanGiven(TransactionHeader.of(LOAN_TX_ID, USER_ID, DATE, "Empréstimo João"), Money.of("300.00"),
                AccountTestFactory.umaContaCorrente(), new NewDebt(LOAN_DEBT_ID, PersonTestFactory.umJoao(), EXPECTED, "almoço"));
    }

    public static Debt umaDividaComOPedro() {
        return Debt.withoutMovement(PAYABLE_DEBT_ID, USER_ID, PersonTestFactory.umPedro(), DebtDirection.PAYABLE,
                Money.of("200.00"), DATE, EXPECTED, "show");
    }

    public static TransactionHeader umHeader(UUID id) {
        return TransactionHeader.of(id, USER_ID, DATE, null);
    }
}
