package com.moneylog.core.finance.app.migration;

import static com.moneylog.core.util.Ids.USER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import com.moneylog.core.finance.app.gateways.FinancialRecordsGateway;
import com.moneylog.core.finance.app.migration.ApplyShareMigrationUseCase.ApplyShareMigrationCommand;
import com.moneylog.core.finance.app.transactions.ShareSettlement;
import com.moneylog.core.finance.util.FinanceScenarioFactory;
import com.moneylog.core.sk.id.IdGenerator;
import com.moneylog.core.sk.ledger.LedgerWriteGateway;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.core.thirdparty.app.gateways.ShareMigrationGateway;
import com.moneylog.core.thirdparty.app.gateways.ThirdPartyExpenseGateway;
import com.moneylog.core.thirdparty.domain.ThirdPartyExpense;
import com.moneylog.core.thirdparty.domain.ThirdPartyReceipt;
import com.moneylog.core.thirdparty.exception.ThirdPartyExceptions.ShareMigrationReportMismatchException;
import com.moneylog.core.transaction.app.gateways.TransactionGateway;
import com.moneylog.core.transaction.app.gateways.TransactionRevisionGateway;
import com.moneylog.core.transaction.domain.Transaction;

@DisplayName("A2c: a migração relê o plano depois das travas e recusa se um recebimento legado apareceu no meio")
class ApplyShareMigrationUseCaseImplTest {

    private final FinancialRecordsGateway records = mock(FinancialRecordsGateway.class);
    private final ThirdPartyExpenseGateway marks = mock(ThirdPartyExpenseGateway.class);
    private final ShareMigrationGateway migrations = mock(ShareMigrationGateway.class);
    private final TransactionGateway transactions = mock(TransactionGateway.class);
    private final TransactionRevisionGateway revisions = mock(TransactionRevisionGateway.class);
    private final LedgerWriteGateway ledger = mock(LedgerWriteGateway.class);
    private final ShareSettlement shareSettlement = mock(ShareSettlement.class);
    private final IdGenerator ids = mock(IdGenerator.class);
    private final TimeProvider time = mock(TimeProvider.class);
    private final FinanceScenarioFactory s = FinanceScenarioFactory.umCenarioBase();

    private final ApplyShareMigrationUseCaseImpl useCase = new ApplyShareMigrationUseCaseImpl(records, marks, migrations, transactions,
            revisions, ledger, shareSettlement, ids, time);

    @Test
    @DisplayName("recebimento legado gravado entre a leitura e a trava muda o hash: ReportMismatch e nada é gravado")
    void deveRecusarQuandoUmRecebimentoLegadoApareceDepoisDaTrava() {
        Transaction purchase = s.compraNoCartao("100.00", 1);
        UUID person = UUID.randomUUID();
        ThirdPartyExpense mark = new ThirdPartyExpense(UUID.randomUUID(), USER_ID, purchase.id(), person, null, null);
        ThirdPartyReceipt late = new ThirdPartyReceipt(UUID.randomUUID(), USER_ID, person, purchase.id(), Money.of("10.00"),
                LocalDate.of(2026, 10, 12), "tarde");
        when(records.load(USER_ID)).thenReturn(s.records());
        when(marks.findAll(USER_ID)).thenReturn(List.of(mark));
        when(marks.findAllReceipts(USER_ID)).thenReturn(List.of(), List.of(late));
        String hash = ShareMigrationPlanner.report(ShareMigrationPlanner.plan(List.of(purchase), List.of(mark), List.of())).hash();

        assertThatThrownBy(() -> useCase.handle(new ApplyShareMigrationCommand(USER_ID, hash, true)))
                .isInstanceOf(ShareMigrationReportMismatchException.class);

        InOrder order = inOrder(transactions, shareSettlement);
        order.verify(transactions).lockForUpdate(USER_ID, purchase.id());
        order.verify(shareSettlement).lockPersons(any(), any());
        verify(marks, org.mockito.Mockito.times(2)).findAllReceipts(USER_ID);
        verify(transactions, never()).update(any());
        verify(migrations, never()).archive(any());
        assertThat(hash).isNotBlank();
    }
}
