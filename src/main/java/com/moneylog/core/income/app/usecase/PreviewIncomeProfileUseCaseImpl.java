package com.moneylog.core.income.app.usecase;

import com.moneylog.core.income.app.gateways.IncomeProfileGateway;
import com.moneylog.core.income.app.gateways.TaxTableGateway;
import com.moneylog.core.income.domain.IncomePreview;
import com.moneylog.core.income.exception.IncomeExceptions.IncomeProfileNotFoundException;

public class PreviewIncomeProfileUseCaseImpl implements PreviewIncomeProfileUseCase {

    private final IncomeProfileGateway profiles;
    private final TaxTableGateway tables;

    public PreviewIncomeProfileUseCaseImpl(IncomeProfileGateway profiles, TaxTableGateway tables) {
        this.profiles = profiles;
        this.tables = tables;
    }

    @Override
    public IncomePreview handle(PreviewIncomeProfileQuery query) {
        var profile = profiles.findById(query.userId(), query.profileId())
                .orElseThrow(() -> new IncomeProfileNotFoundException(query.profileId())).value();
        return IncomeTaxTables.preview(tables, profile, query.month());
    }
}
