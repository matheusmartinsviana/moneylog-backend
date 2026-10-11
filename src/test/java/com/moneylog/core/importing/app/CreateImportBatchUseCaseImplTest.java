package com.moneylog.core.importing.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.moneylog.core.importing.app.gateways.ExistingEntriesGateway;
import com.moneylog.core.importing.app.gateways.ImportBatchGateway;
import com.moneylog.core.importing.app.gateways.ImportBatchGateway.Counts;
import com.moneylog.core.importing.app.gateways.ImportHistoryGateway;
import com.moneylog.core.importing.app.gateways.ImportHistoryGateway.HistoryRow;
import com.moneylog.core.importing.app.gateways.ImportIdempotencyGateway;
import com.moneylog.core.importing.app.gateways.ImportSourceGateway;
import com.moneylog.core.importing.app.usecase.CreateImportBatchUseCase.CreateImportBatchCommand;
import com.moneylog.core.importing.app.usecase.CreateImportBatchUseCaseImpl;
import com.moneylog.core.importing.domain.CategorySuggester.Confidence;
import com.moneylog.core.importing.domain.DedupKey;
import com.moneylog.core.importing.domain.ExistingEntry;
import com.moneylog.core.importing.domain.ImportBatch;
import com.moneylog.core.importing.domain.ImportBatchLine;
import com.moneylog.core.importing.domain.ImportBatchStatus;
import com.moneylog.core.importing.domain.ImportDirection;
import com.moneylog.core.importing.domain.ImportLine;
import com.moneylog.core.importing.domain.ImportLineFlag;
import com.moneylog.core.importing.domain.ImportLineStatus;
import com.moneylog.core.importing.exception.ImportingExceptions.ImportFormatUnsupportedException;
import com.moneylog.core.importing.exception.ImportingExceptions.ImportIdempotencyKeyReusedException;
import com.moneylog.core.importing.exception.ImportingExceptions.ImportSourceNotFoundException;
import com.moneylog.core.importing.exception.ImportingExceptions.ImportSourceRequiredException;
import com.moneylog.core.importing.exception.ImportingExceptions.ImportTooLargeException;
import com.moneylog.core.importing.parser.ColumnMapping;
import com.moneylog.core.sk.id.IdGenerator;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.time.ClockTimeProvider;

@DisplayName("CreateImportBatchUseCaseImpl (T124)")
class CreateImportBatchUseCaseImplTest {

    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID NUBANK = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID XP = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
    private static final UUID CARD = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    private static final UUID FUEL = UUID.fromString("00000000-0000-0000-0000-0000000000f2");

    private static final String CSV = """
            Data,Valor,Descrição
            05/10/2026,-8.50,Café Central
            05/10/2026,-8.50,Café Central
            06/10/2026,5000.00,Salário
            """;

    private ImportBatchGateway batches;
    private ExistingEntriesGateway existing;
    private ImportHistoryGateway history;
    private ImportSourceGateway sources;
    private ImportIdempotencyGateway idempotency;
    private CreateImportBatchUseCaseImpl useCase;

    @BeforeEach
    void setUp() {
        batches = mock(ImportBatchGateway.class);
        existing = mock(ExistingEntriesGateway.class);
        history = mock(ImportHistoryGateway.class);
        sources = mock(ImportSourceGateway.class);
        idempotency = mock(ImportIdempotencyGateway.class);
        AtomicLong sequence = new AtomicLong(1);
        IdGenerator ids = () -> new UUID(0, sequence.getAndIncrement());
        Clock clock = Clock.fixed(Instant.parse("2026-10-10T15:00:00Z"), ZoneOffset.UTC);
        useCase = new CreateImportBatchUseCaseImpl(batches, existing, history, sources, idempotency, ids,
                new ClockTimeProvider(clock, ZoneId.of("America/Sao_Paulo")));
        when(sources.accountExists(USER, NUBANK)).thenReturn(true);
        when(sources.cardExists(USER, CARD)).thenReturn(true);
        when(existing.findForSource(any(), any(), anyBoolean(), any(), any())).thenReturn(List.of());
        when(existing.findInOtherAccounts(any(), any(), any(), any())).thenReturn(List.of());
        when(history.recentCategorized(any(), anyInt())).thenReturn(List.of());
        when(history.rules(any())).thenReturn(List.of());
        when(history.findMapping(any(), any())).thenReturn(Optional.empty());
        when(batches.findSameFile(any(), any(), any())).thenReturn(Optional.empty());
        when(batches.counts(any(), any())).thenReturn(new Counts(3, 3, 0, 0, 0, 0, 0));
    }

    private CreateImportBatchCommand command(UUID account, UUID card, String content) {
        return new CreateImportBatchCommand(USER, account, card, content.getBytes(StandardCharsets.UTF_8), null, null, null);
    }

    @SuppressWarnings("unchecked")
    private List<ImportBatchLine> insertedLines() {
        ArgumentCaptor<List<ImportBatchLine>> captor = ArgumentCaptor.forClass(List.class);
        verify(batches).insert(any(ImportBatch.class), captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("CSV: lote PENDING_REVIEW com as linhas PENDING e descrição normalizada; só grava o lote e as linhas")
    void criaLote() {
        var view = useCase.handle(command(NUBANK, null, CSV));
        assertThat(view.batch().status()).isEqualTo(ImportBatchStatus.PENDING_REVIEW);
        assertThat(view.batch().accountId()).isEqualTo(NUBANK);
        assertThat(view.batch().format()).isEqualTo("CSV");
        List<ImportBatchLine> lines = insertedLines();
        assertThat(lines).hasSize(3).allSatisfy(l -> assertThat(l.status()).isEqualTo(ImportLineStatus.PENDING));
        assertThat(lines.get(0).line().normalizedDescription()).isEqualTo("CAFE CENTRAL");
        assertThat(lines).extracting(ImportBatchLine::position).containsExactly(1, 2, 3);
        verify(batches, never()).update(any());
    }

    @Test
    @DisplayName("dois cafés idênticos no mesmo dia têm chaves distintas (ordinais 1 e 2)")
    void ordinais() {
        useCase.handle(command(NUBANK, null, CSV));
        List<ImportBatchLine> lines = insertedLines();
        assertThat(lines.get(0).review().key()).isNotEqualTo(lines.get(1).review().key());
    }

    @Test
    @DisplayName("reimportar: linhas cujas chaves já existem em movimentações ativas viram DUPLICATE; arquivo igual avisa SAME_FILE_ALREADY_IMPORTED")
    void reimportar() {
        List<ImportLine> original = List.of(
                ImportLine.of(NUBANK, LocalDate.of(2026, 10, 5), Money.of("8.50"), ImportDirection.OUT, "Café Central"),
                ImportLine.of(NUBANK, LocalDate.of(2026, 10, 5), Money.of("8.50"), ImportDirection.OUT, "Café Central"),
                ImportLine.of(NUBANK, LocalDate.of(2026, 10, 6), Money.of("5000.00"), ImportDirection.IN, "Salário"));
        List<DedupKey> keys = DedupKey.forFile(original);
        List<ExistingEntry> entries = new java.util.ArrayList<>();
        for (int i = 0; i < 3; i++) {
            ImportLine l = original.get(i);
            entries.add(new ExistingEntry(new UUID(1, i), NUBANK, l.date(), l.amount(), l.direction(), keys.get(i), keys.get(i), false, true));
        }
        when(existing.findForSource(eq(USER), eq(NUBANK), eq(false), any(), any())).thenReturn(entries);
        when(batches.findSameFile(eq(USER), eq(NUBANK), any())).thenReturn(Optional.of(new UUID(9, 9)));

        var view = useCase.handle(command(NUBANK, null, CSV));

        assertThat(insertedLines()).allSatisfy(l -> assertThat(l.status()).isEqualTo(ImportLineStatus.DUPLICATE));
        assertThat(view.batch().warnings()).containsExactly(ImportBatch.WARNING_SAME_FILE);
    }

    @Test
    @DisplayName("movimentação manual parecida (mesmo valor, ±1 dia) vira POSSIBLE_DUPLICATE com matches")
    void possivelDuplicata() {
        ExistingEntry manual = new ExistingEntry(new UUID(2, 2), NUBANK, LocalDate.of(2026, 10, 6), Money.of("8.50"), ImportDirection.OUT, null, null,
                true, true);
        when(existing.findForSource(eq(USER), eq(NUBANK), eq(false), any(), any())).thenReturn(List.of(manual));
        useCase.handle(command(NUBANK, null, CSV));
        List<ImportBatchLine> lines = insertedLines();
        assertThat(lines.get(0).status()).isEqualTo(ImportLineStatus.POSSIBLE_DUPLICATE);
        assertThat(lines.get(0).review().matches()).containsExactly(manual.transactionId());
        assertThat(lines.get(2).status()).isEqualTo(ImportLineStatus.PENDING);
    }

    @Test
    @DisplayName("sugestão por histórico: 3 ocorrências = HIGH")
    void sugestao() {
        when(history.recentCategorized(eq(USER), anyInt())).thenReturn(List.of(
                new HistoryRow(ImportDirection.OUT, "Café Central", FUEL, "PIX"), new HistoryRow(ImportDirection.OUT, "CAFÉ CENTRAL", FUEL, "PIX"),
                new HistoryRow(ImportDirection.OUT, "café central!", FUEL, "PIX")));
        useCase.handle(command(NUBANK, null, CSV));
        var suggestion = insertedLines().get(0).suggestion();
        assertThat(suggestion.categoryId()).isEqualTo(FUEL);
        assertThat(suggestion.confidence()).isEqualTo(Confidence.HIGH);
        assertThat(insertedLines().get(2).suggestion()).isNull();
    }

    @Test
    @DisplayName("perna oposta em outra conta marca POSSIBLE_TRANSFER e exige decisão (S-167)")
    void transferencia() {
        when(existing.findInOtherAccounts(eq(USER), eq(NUBANK), any(), any())).thenReturn(List.of(
                new ExistingEntry(new UUID(3, 3), XP, LocalDate.of(2026, 10, 6), Money.of("5000.00"), ImportDirection.OUT, null, null, false, true)));
        useCase.handle(command(NUBANK, null, CSV));
        ImportBatchLine salary = insertedLines().get(2);
        assertThat(salary.flags()).containsExactly(ImportLineFlag.POSSIBLE_TRANSFER);
        assertThat(salary.requiresDecision()).isTrue();
    }

    @Test
    @DisplayName("cartão: pagamento de fatura nasce rejeitado; parcela exige decisão; compra comum fica pendente; nada consulta outras contas")
    void cartao() {
        String statement = """
                Data,Valor,Descrição
                05/10/2026,-1283.00,PAGAMENTO RECEBIDO
                06/10/2026,200.00,PARC 2/6 NOTEBOOK
                07/10/2026,50.00,Mercado
                """;
        ColumnMapping mapping = new ColumnMapping(0, 2, 1, null, null, true, true, ',');
        var view = useCase.handle(new CreateImportBatchCommand(USER, null, CARD, statement.getBytes(StandardCharsets.UTF_8), "CSV", mapping, null));
        assertThat(view.batch().cardId()).isEqualTo(CARD);
        List<ImportBatchLine> lines = insertedLines();
        assertThat(lines.get(0).status()).isEqualTo(ImportLineStatus.REJECTED);
        assertThat(lines.get(0).flags()).containsExactly(ImportLineFlag.LOOKS_LIKE_INVOICE_PAYMENT);
        assertThat(lines.get(1).requiresDecision()).isTrue();
        assertThat(lines.get(2).status()).isEqualTo(ImportLineStatus.PENDING);
        verify(existing, never()).findInOtherAccounts(any(), any(), any(), any());
        verify(history).saveMapping(USER, CARD, mapping);
    }

    @Test
    @DisplayName("fonte inválida: os dois ou nenhum = erro; conta/cartão de outro usuário não existe (404)")
    void fonte() {
        assertThatThrownBy(() -> useCase.handle(command(NUBANK, CARD, CSV))).isInstanceOf(ImportSourceRequiredException.class);
        assertThatThrownBy(() -> useCase.handle(command(null, null, CSV))).isInstanceOf(ImportSourceRequiredException.class);
        assertThatThrownBy(() -> useCase.handle(command(XP, null, CSV))).isInstanceOf(ImportSourceNotFoundException.class);
        verify(batches, never()).insert(any(), any());
    }

    @Test
    @DisplayName("XLSX/PDF e arquivo acima de 5 MB são recusados antes de gravar")
    void formatos() {
        assertThatThrownBy(() -> useCase.handle(new CreateImportBatchCommand(USER, NUBANK, null, new byte[] {'P', 'K', 3, 4, 0, 0, 0}, null, null, null)))
                .isInstanceOf(ImportFormatUnsupportedException.class);
        assertThatThrownBy(() -> useCase.handle(new CreateImportBatchCommand(USER, NUBANK, null, new byte[5 * 1024 * 1024 + 1], null, null, null)))
                .isInstanceOf(ImportTooLargeException.class);
        verify(batches, never()).insert(any(), any());
    }

    @Test
    @DisplayName("Idempotency-Key repetida com o mesmo arquivo devolve o mesmo lote, sem duplicar linhas; com outro arquivo é 409")
    void idempotencia() {
        UUID key = new UUID(5, 5);
        ImportBatch previous = ImportBatch.open(new UUID(7, 7), USER, NUBANK, null, "CSV", "b".repeat(64), Instant.parse("2026-10-10T12:00:00Z"),
                List.of(), List.of());
        when(idempotency.claim(any(), any(), eq(key), any(), any(), any(), any())).thenReturn(false);
        var captor = ArgumentCaptor.forClass(String.class);
        when(idempotency.find(eq(USER), any(), eq(key))).thenAnswer(inv -> Optional.of(new ImportIdempotencyGateway.Claim(lastHash(captor), previous.id())));
        when(batches.find(USER, previous.id())).thenReturn(Optional.of(previous));

        when(idempotency.claim(any(), any(), eq(key), captor.capture(), any(), any(), any())).thenReturn(false);
        var replay = useCase.handle(new CreateImportBatchCommand(USER, NUBANK, null, CSV.getBytes(StandardCharsets.UTF_8), null, null, key));

        assertThat(replay.batch().id()).isEqualTo(previous.id());
        verify(batches, never()).insert(any(), any());

        when(idempotency.find(eq(USER), any(), eq(key))).thenReturn(Optional.of(new ImportIdempotencyGateway.Claim("outro-hash", previous.id())));
        assertThatThrownBy(() -> useCase.handle(new CreateImportBatchCommand(USER, NUBANK, null, CSV.getBytes(StandardCharsets.UTF_8), null, null, key)))
                .isInstanceOf(ImportIdempotencyKeyReusedException.class);
    }

    private static String lastHash(ArgumentCaptor<String> captor) {
        return captor.getAllValues().get(captor.getAllValues().size() - 1);
    }
}
