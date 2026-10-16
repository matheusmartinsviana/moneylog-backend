package com.moneylog.core.finance.app.networth.usecase;

import java.time.LocalDate;

import com.moneylog.core.asset.app.gateways.AssetGateway;
import com.moneylog.core.finance.app.statement.GetOwnerStatementUseCase;
import com.moneylog.core.finance.app.statement.GetOwnerStatementUseCase.GetOwnerStatementQuery;
import com.moneylog.core.finance.app.gateways.FinancialRecordsGateway;
import com.moneylog.core.finance.domain.CashPosition;
import com.moneylog.core.finance.domain.DebtOverviewCalculator;
import com.moneylog.core.finance.domain.FinancialRecords;
import com.moneylog.core.finance.domain.Ledger;
import com.moneylog.core.finance.domain.NetWorth;
import com.moneylog.core.finance.domain.NetWorthOverview;
import com.moneylog.core.finance.domain.OwnerStatement;
import com.moneylog.core.sk.exception.BusinessRuleException;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.time.Competence;
import com.moneylog.core.sk.time.TimeProvider;

public class GetNetWorthOverviewUseCaseImpl implements GetNetWorthOverviewUseCase {

    private final FinancialRecordsGateway records;
    private final GetOwnerStatementUseCase statement;
    private final AssetGateway assets;
    private final TimeProvider time;

    public GetNetWorthOverviewUseCaseImpl(FinancialRecordsGateway records, GetOwnerStatementUseCase statement, AssetGateway assets,
                                          TimeProvider time) {
        this.records = records;
        this.statement = statement;
        this.assets = assets;
        this.time = time;
    }

    @Override
    public NetWorthOverview handle(GetNetWorthOverviewQuery query) {
        Integer target = query.targetMonths();
        if (target != null && (target < NetWorthOverview.MIN_TARGET_MONTHS || target > NetWorthOverview.MAX_TARGET_MONTHS)) {
            throw new InvalidTargetMonthsException(target);
        }
        LocalDate today = time.today();
        Competence month = query.month() == null ? Competence.from(today) : query.month();
        FinancialRecords loaded = records.load(query.userId());
        Ledger ledger = loaded.ledger();
        Money cash = CashPosition.at(loaded, ledger, today).inAccounts();
        Money receivables = NetWorth.at(ledger, today).receivables();
        Money debts = DebtOverviewCalculator.total(loaded, today);
        OwnerStatement.MonthColumn column = statement.handle(new GetOwnerStatementQuery(query.userId(), month, 1, today)).months().get(0);
        Money base = column.fixedBase();
        return NetWorthOverview.of(cash, assets.findActive(query.userId()), receivables, debts, base, target);
    }

    public static class InvalidTargetMonthsException extends BusinessRuleException {
        public InvalidTargetMonthsException(int value) {
            super("TARGET_MONTHS_INVALID", "Meta de cobertura inválida: " + value + ". Use de "
                    + NetWorthOverview.MIN_TARGET_MONTHS + " a " + NetWorthOverview.MAX_TARGET_MONTHS + ".");
        }
    }
}
