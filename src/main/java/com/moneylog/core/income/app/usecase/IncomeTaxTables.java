package com.moneylog.core.income.app.usecase;

import com.moneylog.core.income.app.gateways.TaxTableGateway;
import com.moneylog.core.income.domain.IncomePreview;
import com.moneylog.core.income.domain.IncomePreviewCalculator;
import com.moneylog.core.income.domain.IncomeProfile;
import com.moneylog.core.income.domain.TaxKind;
import com.moneylog.core.income.domain.TaxTable;
import com.moneylog.core.income.exception.IncomeExceptions.TaxTableUnavailableException;
import com.moneylog.core.sk.time.Competence;

public final class IncomeTaxTables {

    private IncomeTaxTables() {
    }

    public static IncomePreview preview(TaxTableGateway tables, IncomeProfile profile, Competence month) {
        var inssReference = month.firstDay();
        var irrfReference = profile.balanceDate(month);
        TaxTable inss = tables.findEffective(TaxKind.INSS, inssReference)
                .orElseThrow(() -> new TaxTableUnavailableException(TaxKind.INSS, inssReference));
        TaxTable irrf = tables.findEffective(TaxKind.IRRF, irrfReference)
                .orElseThrow(() -> new TaxTableUnavailableException(TaxKind.IRRF, irrfReference));
        return IncomePreviewCalculator.calculate(profile, month, inss, irrf);
    }
}
