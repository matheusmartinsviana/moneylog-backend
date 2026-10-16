package com.moneylog.core.finance.app.migration;

import com.moneylog.core.finance.app.gateways.FinancialRecordsGateway;
import com.moneylog.core.thirdparty.app.gateways.ShareMigrationGateway;
import com.moneylog.core.thirdparty.app.gateways.ThirdPartyExpenseGateway;
import com.moneylog.core.thirdparty.domain.ShareMigrationReport;

public class PreviewShareMigrationUseCaseImpl implements PreviewShareMigrationUseCase {

    private final FinancialRecordsGateway records;
    private final ThirdPartyExpenseGateway marks;
    private final ShareMigrationGateway migrations;

    public PreviewShareMigrationUseCaseImpl(FinancialRecordsGateway records, ThirdPartyExpenseGateway marks, ShareMigrationGateway migrations) {
        this.records = records;
        this.marks = marks;
        this.migrations = migrations;
    }

    @Override
    public ShareMigrationReport handle(PreviewShareMigrationQuery query) {
        var plans = ShareMigrationPlanner.plan(records.load(query.userId()).transactions(), marks.findAll(query.userId()),
                marks.findAllReceipts(query.userId()));
        ShareMigrationReport report = ShareMigrationPlanner.report(plans);
        migrations.saveReport(query.userId(), report);
        return report;
    }
}
