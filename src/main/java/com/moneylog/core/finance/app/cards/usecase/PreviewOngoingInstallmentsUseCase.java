package com.moneylog.core.finance.app.cards.usecase;

import com.moneylog.core.finance.app.cards.OngoingPreviewView;
import com.moneylog.core.finance.app.cards.usecase.CreateOngoingInstallmentsUseCase.CreateOngoingInstallmentsCommand;

public interface PreviewOngoingInstallmentsUseCase {

    /** O que {@link CreateOngoingInstallmentsUseCase} gravaria, pelo mesmo caminho de código e sem gravar nem travar. */
    OngoingPreviewView handle(CreateOngoingInstallmentsCommand command);
}
