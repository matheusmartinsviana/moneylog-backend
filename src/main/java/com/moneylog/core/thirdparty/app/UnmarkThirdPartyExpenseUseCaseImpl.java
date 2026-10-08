package com.moneylog.core.thirdparty.app;

import com.moneylog.core.thirdparty.app.gateways.ThirdPartyExpenseGateway;
import com.moneylog.core.thirdparty.exception.ThirdPartyExceptions.ThirdPartyExpenseNotFoundException;

public class UnmarkThirdPartyExpenseUseCaseImpl implements UnmarkThirdPartyExpenseUseCase {

    private final ThirdPartyExpenseGateway marks;

    public UnmarkThirdPartyExpenseUseCaseImpl(ThirdPartyExpenseGateway marks) {
        this.marks = marks;
    }

    @Override
    public void handle(UnmarkThirdPartyExpenseCommand c) {
        marks.findById(c.userId(), c.id()).orElseThrow(() -> new ThirdPartyExpenseNotFoundException(c.id()));
        marks.delete(c.userId(), c.id());
    }
}
