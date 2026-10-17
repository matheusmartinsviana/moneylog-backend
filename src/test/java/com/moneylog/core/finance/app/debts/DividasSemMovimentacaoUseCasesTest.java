package com.moneylog.core.finance.app.debts;

import static com.moneylog.core.util.Ids.OTHER_USER_ID;
import static com.moneylog.core.util.Ids.USER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.moneylog.core.category.app.gateways.CategoryGateway;
import com.moneylog.core.category.domain.Category;
import com.moneylog.core.category.util.factory.CategoryTestFactory;
import com.moneylog.core.debt.app.DebtView;
import com.moneylog.core.debt.domain.Debt;
import com.moneylog.core.debt.domain.DebtDirection;
import com.moneylog.core.debt.domain.DebtStatus;
import com.moneylog.core.debt.domain.Person;
import com.moneylog.core.debt.exception.DebtExceptions.DebtNotFoundException;
import com.moneylog.core.debt.exception.DebtExceptions.DebtOperationRefusedException;
import com.moneylog.core.debt.exception.DebtExceptions.DebtReasonRequiredException;
import com.moneylog.core.debt.exception.DebtExceptions.PersonArchivedException;
import com.moneylog.core.debt.exception.DebtExceptions.PersonNotFoundException;
import com.moneylog.core.debt.util.InMemoryDebtGateway;
import com.moneylog.core.debt.util.InMemoryPersonGateway;
import com.moneylog.core.debt.util.factory.PersonTestFactory;
import com.moneylog.core.finance.app.debts.usecase.CancelDebtUseCase.CancelDebtCommand;
import com.moneylog.core.finance.app.debts.usecase.CancelDebtUseCaseImpl;
import com.moneylog.core.finance.app.debts.usecase.CreateDebtUseCase.CreateDebtCommand;
import com.moneylog.core.finance.app.debts.usecase.CreateDebtUseCaseImpl;
import com.moneylog.core.finance.app.debts.usecase.UpdateDebtUseCase.UpdateDebtCommand;
import com.moneylog.core.finance.app.debts.usecase.UpdateDebtUseCaseImpl;
import com.moneylog.core.finance.app.debts.usecase.VoidDebtRecordUseCase.VoidDebtRecordCommand;
import com.moneylog.core.finance.app.debts.usecase.VoidDebtRecordUseCaseImpl;
import com.moneylog.core.sk.ledger.LedgerWriteGateway;
import com.moneylog.core.sk.exception.VersionConflictException;
import com.moneylog.core.sk.id.IdGenerator;
import com.moneylog.core.sk.ledger.BookType;
import com.moneylog.core.sk.ledger.LedgerEntry;
import com.moneylog.core.sk.ledger.PostingSourceType;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.core.transaction.app.gateways.IdempotencyGateway;
import com.moneylog.core.transaction.app.gateways.IdempotencyGateway.Claim;
import com.moneylog.core.transaction.exception.TransactionExceptions.IdempotencyKeyReusedException;

@DisplayName("Dívida sem movimentação, edição, perdão e estorno do registro: casos de uso (T77)")
class DividasSemMovimentacaoUseCasesTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 10);
    private static final Instant NOW = Instant.parse("2026-10-10T15:00:00Z");

    private final InMemoryPersonGateway people = new InMemoryPersonGateway();
    private final InMemoryDebtGateway debts = new InMemoryDebtGateway();
    private final CategoryGateway categories = mock(CategoryGateway.class);
    private final LedgerWriteGateway ledger = mock(LedgerWriteGateway.class);
    private final TimeProvider time = mock(TimeProvider.class);
    private final IdempotencyGateway idempotency = mock(IdempotencyGateway.class);
    private final Category food = CategoryTestFactory.umaCategoriaDeDespesa();
    private final Person maria = PersonTestFactory.umPedro();
    private int sequence = 1;
    private final IdGenerator ids = () -> UUID.fromString(String.format("00000000-0000-0000-0000-0000000b%04d", sequence++));

    private CreateDebtUseCaseImpl create;
    private UpdateDebtUseCaseImpl update;
    private CancelDebtUseCaseImpl cancel;
    private VoidDebtRecordUseCaseImpl voidRecord;

    @BeforeEach
    void setUp() {
        when(time.today()).thenReturn(TODAY);
        when(time.now()).thenReturn(NOW);
        when(categories.findById(USER_ID, food.id())).thenReturn(Optional.of(food));
        people.insert(maria);
        create = new CreateDebtUseCaseImpl(people, debts, categories, ledger, idempotency, ids, time);
        update = new UpdateDebtUseCaseImpl(people, debts, categories, time);
        cancel = new CancelDebtUseCaseImpl(people, debts, categories, ledger, time);
        voidRecord = new VoidDebtRecordUseCaseImpl(people, debts, categories, ledger, time);
    }

    private DebtView payable(String amount, UUID categoryId) {
        return create.handle(new CreateDebtCommand(USER_ID, maria.id(), DebtDirection.PAYABLE, Money.of(amount), TODAY,
                LocalDate.of(2026, 11, 5), "Almoço pago por Maria", categoryId));
    }

    @Test
    @DisplayName("criar: insere a dívida e grava o reconhecimento no razão (PAYABLE −X / EQUITY +X, caixa 0); com categoria, EXPENSE +X")
    void deveCriar() {
        DebtView created = payable("200.00", null);
        assertThat(created.debt().status()).isEqualTo(DebtStatus.OPEN);
        assertThat(created.stored().version()).isZero();
        assertThat(debts.findById(USER_ID, created.debt().id())).isPresent();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LedgerEntry>> entries = ArgumentCaptor.forClass(List.class);
        verify(ledger).append(eq(USER_ID), entries.capture());
        assertThat(entries.getValue()).hasSize(2);
        assertThat(entries.getValue()).extracting(e -> e.posting().bookType()).containsExactlyInAnyOrder(BookType.PAYABLE, BookType.EQUITY);
        assertThat(entries.getValue()).allSatisfy(e -> assertThat(e.source().type()).isEqualTo(PostingSourceType.DEBT_RECOGNITION));
        assertThat(people.locks()).isEqualTo(1);

        DebtView consumed = payable("80.00", food.id());
        assertThat(consumed.debt().categoryId()).isEqualTo(food.id());
        assertThat(consumed.categoryName()).isEqualTo(food.name());
        verify(ledger, org.mockito.Mockito.times(2)).append(eq(USER_ID), any());
    }

    @Test
    @DisplayName("S-237: com Idempotency-Key a chave é reivindicada na criação (escopo próprio, hash do comando); a mesma chave e o mesmo comando devolve a dívida atual sem novo lançamento; outro comando é IDEMPOTENCY_KEY_REUSED")
    void deveSerIdempotente() {
        UUID key = UUID.fromString("00000000-0000-0000-0000-0000000a0002");
        when(idempotency.claim(eq(USER_ID), any(), eq(key), any(), any(), any(), any())).thenReturn(true);
        DebtView first = create.handle(command("80.00", food.id(), key));
        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<UUID> resource = ArgumentCaptor.forClass(UUID.class);
        verify(idempotency).claim(eq(USER_ID), eq("POST /v1/debts"), eq(key), hash.capture(), resource.capture(), any(), any());
        assertThat(resource.getValue()).isEqualTo(first.debt().id());

        when(idempotency.claim(eq(USER_ID), any(), eq(key), any(), any(), any(), any())).thenReturn(false);
        when(idempotency.find(USER_ID, "POST /v1/debts", key)).thenReturn(Optional.of(new Claim(hash.getValue(), first.debt().id(), NOW)));
        DebtView replay = create.handle(command("80.00", food.id(), key));
        assertThat(replay.debt().id()).isEqualTo(first.debt().id());
        verify(ledger, org.mockito.Mockito.times(1)).append(eq(USER_ID), any());
        assertThatThrownBy(() -> create.handle(command("81.00", food.id(), key))).isInstanceOf(IdempotencyKeyReusedException.class);
        verify(ledger, org.mockito.Mockito.times(1)).append(eq(USER_ID), any());
        create.handle(command("80.00", food.id(), null));
        verify(idempotency, org.mockito.Mockito.times(3)).claim(any(), any(), any(), any(), any(), any(), any());
    }

    private CreateDebtCommand command(String amount, UUID categoryId, UUID key) {
        return new CreateDebtCommand(USER_ID, maria.id(), DebtDirection.PAYABLE, Money.of(amount), TODAY, LocalDate.of(2026, 11, 5),
                "Jantar", categoryId, key);
    }

    @Test
    @DisplayName("criar recusa pessoa inexistente/de outro usuário, arquivada, categoria desconhecida e categoria numa RECEIVABLE; nada é gravado")
    void deveRecusarNaCriacao() {
        assertThatThrownBy(() -> create.handle(new CreateDebtCommand(USER_ID, UUID.randomUUID(), DebtDirection.PAYABLE, Money.of("1.00"), TODAY,
                null, "x", null))).isInstanceOf(PersonNotFoundException.class);
        assertThatThrownBy(() -> create.handle(new CreateDebtCommand(OTHER_USER_ID, maria.id(), DebtDirection.PAYABLE, Money.of("1.00"), TODAY,
                null, "x", null))).isInstanceOf(PersonNotFoundException.class);
        assertThatThrownBy(() -> create.handle(new CreateDebtCommand(USER_ID, maria.id(), DebtDirection.PAYABLE, Money.of("1.00"), TODAY,
                null, "x", UUID.randomUUID()))).isInstanceOf(com.moneylog.core.category.exception.CategoryExceptions.CategoryNotFoundException.class);
        assertThatThrownBy(() -> create.handle(new CreateDebtCommand(USER_ID, maria.id(), DebtDirection.RECEIVABLE, Money.of("1.00"), TODAY,
                null, "x", food.id()))).isInstanceOf(com.moneylog.core.debt.exception.DebtExceptions.DebtCategoryNotAllowedException.class);
        Person archived = Person.restore(UUID.fromString("00000000-0000-0000-0000-0000000f00aa"), USER_ID, "Arquivada", null, true);
        people.insert(archived);
        assertThatThrownBy(() -> create.handle(new CreateDebtCommand(USER_ID, archived.id(), DebtDirection.PAYABLE, Money.of("1.00"), TODAY,
                null, "x", null))).isInstanceOf(PersonArchivedException.class);
        verify(ledger, never()).append(any(), any());
    }

    @Test
    @DisplayName("editar: muda previsão e descrição, sobe a versão; versão velha, dívida de outro usuário e dívida encerrada são recusadas")
    void deveEditar() {
        DebtView created = payable("200.00", null);
        UUID id = created.debt().id();
        DebtView edited = update.handle(new UpdateDebtCommand(USER_ID, id, LocalDate.of(2026, 12, 1), "Almoço de domingo", 0));
        assertThat(edited.debt().expectedDate()).isEqualTo(LocalDate.of(2026, 12, 1));
        assertThat(edited.debt().description()).isEqualTo("Almoço de domingo");
        assertThat(edited.debt().principal()).isEqualTo(Money.of("200.00"));
        assertThat(edited.stored().version()).isEqualTo(1);
        assertThatThrownBy(() -> update.handle(new UpdateDebtCommand(USER_ID, id, null, "velha", 0))).isInstanceOf(VersionConflictException.class);
        assertThatThrownBy(() -> update.handle(new UpdateDebtCommand(OTHER_USER_ID, id, null, "x", 1))).isInstanceOf(DebtNotFoundException.class);
        cancel.handle(new CancelDebtCommand(USER_ID, id, "perdoada"));
        assertThatThrownBy(() -> update.handle(new UpdateDebtCommand(USER_ID, id, null, "tarde", 2))).isInstanceOf(DebtOperationRefusedException.class)
                .extracting("code").isEqualTo("DEBT_CLOSED");
    }

    @Test
    @DisplayName("perdão: grava só o lançamento do perdão (PAYABLE +s / EQUITY −s, origem DEBT_CANCELLATION); sem motivo, quitada ou de novo é recusado")
    void devePerdoar() {
        DebtView created = payable("200.00", null);
        Debt debt = created.debt();
        debt.registerSettlement(UUID.randomUUID(), Money.of("50.00"));

        assertThatThrownBy(() -> cancel.handle(new CancelDebtCommand(USER_ID, debt.id(), "  "))).isInstanceOf(DebtReasonRequiredException.class);
        DebtView canceled = cancel.handle(new CancelDebtCommand(USER_ID, debt.id(), "a Maria me perdoou"));
        assertThat(canceled.debt().status()).isEqualTo(DebtStatus.CANCELED);
        assertThat(canceled.debt().outstanding()).isEqualTo(Money.of("0.00"));
        assertThat(canceled.cancelable()).isFalse();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LedgerEntry>> entries = ArgumentCaptor.forClass(List.class);
        verify(ledger, org.mockito.Mockito.times(2)).append(eq(USER_ID), entries.capture());
        List<LedgerEntry> cancellation = entries.getAllValues().get(1);
        assertThat(cancellation).hasSize(2);
        assertThat(cancellation).allSatisfy(e -> assertThat(e.source().type()).isEqualTo(PostingSourceType.DEBT_CANCELLATION));
        assertThat(cancellation.stream().filter(e -> e.posting().bookType() == BookType.PAYABLE).findFirst().orElseThrow().posting().amount())
                .isEqualTo(Money.of("150.00"));
        assertThat(debts.locked()).contains(debt.id());
        assertThatThrownBy(() -> cancel.handle(new CancelDebtCommand(USER_ID, debt.id(), "de novo"))).isInstanceOf(DebtOperationRefusedException.class)
                .extracting("code").isEqualTo("DEBT_CLOSED");

        Debt settled = payable("30.00", null).debt();
        settled.registerSettlement(UUID.randomUUID(), Money.of("30.00"));
        assertThatThrownBy(() -> cancel.handle(new CancelDebtCommand(USER_ID, settled.id(), "tarde"))).isInstanceOf(DebtOperationRefusedException.class)
                .extracting("code").isEqualTo("DEBT_ALREADY_SETTLED");
        assertThatThrownBy(() -> cancel.handle(new CancelDebtCommand(USER_ID, UUID.randomUUID(), "x"))).isInstanceOf(DebtNotFoundException.class);
    }

    @Test
    @DisplayName("estorno do registro: VOIDED e desativa o reconhecimento (DEBT_RECOGNITION); com quitação ativa ou já encerrada é recusado e nada é desativado")
    void deveEstornarORegistro() {
        Debt debt = payable("200.00", null).debt();
        DebtView voided = voidRecord.handle(new VoidDebtRecordCommand(USER_ID, debt.id(), "registrei errado"));
        assertThat(voided.debt().status()).isEqualTo(DebtStatus.VOIDED);
        assertThat(voided.debt().closureReason()).isEqualTo("registrei errado");
        verify(ledger).deactivateBySource(eq(USER_ID), eq(PostingSourceType.DEBT_RECOGNITION), eq(debt.id()), eq("VOID"), eq(NOW));
        assertThatThrownBy(() -> voidRecord.handle(new VoidDebtRecordCommand(USER_ID, debt.id(), "de novo")))
                .isInstanceOf(DebtOperationRefusedException.class).extracting("code").isEqualTo("DEBT_CLOSED");

        Debt withSettlement = payable("90.00", null).debt();
        withSettlement.registerSettlement(UUID.randomUUID(), Money.of("10.00"));
        assertThatThrownBy(() -> voidRecord.handle(new VoidDebtRecordCommand(USER_ID, withSettlement.id(), "engano")))
                .isInstanceOf(DebtOperationRefusedException.class).extracting("code").isEqualTo("DEBT_HAS_SETTLEMENTS");
        assertThat(withSettlement.status()).isEqualTo(DebtStatus.PARTIALLY_SETTLED);
        verify(ledger, org.mockito.Mockito.times(1)).deactivateBySource(any(), any(), any(), any(), any());
        Debt another = payable("10.00", null).debt();
        assertThatThrownBy(() -> voidRecord.handle(new VoidDebtRecordCommand(USER_ID, another.id(), null)))
                .isInstanceOf(DebtReasonRequiredException.class);
        assertThat(another.status()).isEqualTo(DebtStatus.OPEN);
    }
}
