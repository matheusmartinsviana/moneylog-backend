package com.moneylog.core.finance.app.debts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.moneylog.core.finance.app.debts.usecase.SetReceivableExpectedMonthUseCase.Result;
import com.moneylog.core.finance.app.debts.usecase.SetReceivableExpectedMonthUseCase.SetReceivableExpectedMonthCommand;
import com.moneylog.core.finance.app.debts.usecase.SetReceivableExpectedMonthUseCaseImpl;
import com.moneylog.core.finance.app.gateways.FinancialRecordsGateway;
import com.moneylog.core.finance.domain.FinancialRecords;
import com.moneylog.core.finance.util.FinanceScenarioFactory;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.time.Competence;
import com.moneylog.core.thirdparty.app.gateways.ThirdPartyExpenseGateway;
import com.moneylog.core.thirdparty.domain.ReceiptSettlement;
import com.moneylog.core.thirdparty.domain.ReceivableExpectedMonth;
import com.moneylog.core.thirdparty.domain.ThirdPartyReceipt;
import com.moneylog.core.thirdparty.exception.ThirdPartyExceptions.ReceivableAlreadyReceivedException;
import com.moneylog.core.thirdparty.exception.ThirdPartyExceptions.ReceivableExpectedMonthInvalidException;
import com.moneylog.core.thirdparty.exception.ThirdPartyExceptions.ReceivableNotFoundException;
import com.moneylog.core.transaction.domain.CardTransactions;
import com.moneylog.core.transaction.domain.ShareLine;
import com.moneylog.core.transaction.domain.SplitMode;
import com.moneylog.core.transaction.domain.Transaction;

@DisplayName("Definir o mês previsto de um recebível")
class SetReceivableExpectedMonthUseCaseTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 15);
    private static final UUID USER = UUID.randomUUID();
    private static final UUID ANA = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    private final FinanceScenarioFactory scenario = FinanceScenarioFactory.umCenarioBase();
    private final FinancialRecordsGateway records = mock(FinancialRecordsGateway.class);
    private final ThirdPartyExpenseGateway marks = mock(ThirdPartyExpenseGateway.class);
    private final SetReceivableExpectedMonthUseCaseImpl useCase = new SetReceivableExpectedMonthUseCaseImpl(records, marks);
    private Transaction tx;
    private UUID first;
    private UUID second;

    @BeforeEach
    void setUp() {
        tx = CardTransactions.purchase(scenario.header(TODAY, "Jantar"), Money.of("300.00"), scenario.roxinho, scenario.alimentacao, 3,
                scenario.chain(), TODAY, SplitMode.BPS, List.of(new ShareLine(null, 5000, null), new ShareLine(ANA, 5000, null)));
        first = com.moneylog.core.card.domain.TransactionInstallment.idFor(tx.id(), 1);
        second = com.moneylog.core.card.domain.TransactionInstallment.idFor(tx.id(), 2);
        when(records.load(USER)).thenReturn(new FinancialRecords(List.of(), List.of(scenario.roxinho), List.of(tx), List.of()));
        when(marks.findAllReceipts(USER)).thenReturn(List.of());
        when(marks.findExpectedMonths(USER)).thenReturn(List.of());
    }

    private SetReceivableExpectedMonthCommand command(UUID installment, Competence month) {
        return new SetReceivableExpectedMonthCommand(USER, tx.id(), installment, ANA, month, null);
    }

    @Test
    @DisplayName("grava o mês escolhido e devolve a parcela com o mês previsto efetivo")
    void gravaEDevolve() {
        Competence december = Competence.parse("2026-12");
        when(marks.saveExpectedMonth(USER, tx.id(), second, ANA, december, null)).thenReturn(new ReceivableExpectedMonth(tx.id(), second, ANA, december, 0));

        Result result = useCase.handle(command(second, december));

        assertThat(result.installment().expectedMonth()).isEqualTo(december);
        assertThat(result.installment().overridden()).isTrue();
        verify(marks).saveExpectedMonth(USER, tx.id(), second, ANA, december, null);
    }

    @Test
    @DisplayName("mês nulo apaga a escolha e volta ao vencimento da fatura")
    void mesNuloVoltaAoPadrao() {
        Result result = useCase.handle(command(second, null));

        assertThat(result.installment().overridden()).isFalse();
        assertThat(result.installment().expectedMonth()).isEqualTo(result.installment().dueMonth());
        verify(marks).deleteExpectedMonth(USER, second, ANA);
        verify(marks, never()).saveExpectedMonth(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("mês fora de 2000-01..2100-12 é recusado sem gravar nada")
    void mesForaDaJanela() {
        assertThatThrownBy(() -> useCase.handle(command(second, Competence.parse("1999-12")))).isInstanceOf(ReceivableExpectedMonthInvalidException.class);
        assertThatThrownBy(() -> useCase.handle(command(second, Competence.parse("2101-01")))).isInstanceOf(ReceivableExpectedMonthInvalidException.class);
        verify(marks, never()).saveExpectedMonth(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("parcela, pessoa ou compra que não existem para o usuário: não encontrado, sem gravar")
    void inexistente() {
        assertThatThrownBy(() -> useCase.handle(command(UUID.randomUUID(), Competence.parse("2026-12")))).isInstanceOf(ReceivableNotFoundException.class);
        assertThatThrownBy(() -> useCase.handle(new SetReceivableExpectedMonthCommand(USER, tx.id(), second, UUID.randomUUID(),
                Competence.parse("2026-12"), null))).isInstanceOf(ReceivableNotFoundException.class);
        verify(marks, never()).saveExpectedMonth(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("parcela já recebida por inteiro não é editável; a seguinte continua editável")
    void jaRecebida() {
        when(marks.findAllReceipts(USER)).thenReturn(List.of(new ThirdPartyReceipt(UUID.randomUUID(), USER, ANA, tx.id(), Money.of("50.00"),
                TODAY, null, null, ReceiptSettlement.ALREADY_IN_BALANCE, true)));
        Competence december = Competence.parse("2026-12");
        when(marks.saveExpectedMonth(eq(USER), eq(tx.id()), eq(second), eq(ANA), eq(december), any())).thenReturn(new ReceivableExpectedMonth(tx.id(), second, ANA, december, 0));

        assertThatThrownBy(() -> useCase.handle(command(first, december))).isInstanceOf(ReceivableAlreadyReceivedException.class);
        assertThat(useCase.handle(command(second, december)).installment().expectedMonth()).isEqualTo(december);
        verify(marks, never()).saveExpectedMonth(eq(USER), eq(tx.id()), eq(first), any(), any(), any());
    }
}
