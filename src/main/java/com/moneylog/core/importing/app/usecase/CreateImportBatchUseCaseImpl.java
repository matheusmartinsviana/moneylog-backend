package com.moneylog.core.importing.app.usecase;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.moneylog.core.importing.app.ImportBatchView;
import com.moneylog.core.importing.app.gateways.ExistingEntriesGateway;
import com.moneylog.core.importing.app.gateways.ImportBatchGateway;
import com.moneylog.core.importing.app.gateways.ImportHistoryGateway;
import com.moneylog.core.importing.app.gateways.ImportIdempotencyGateway;
import com.moneylog.core.importing.app.gateways.ImportSourceGateway;
import com.moneylog.core.importing.domain.CategorySuggester;
import com.moneylog.core.importing.domain.CategorySuggester.Suggestion;
import com.moneylog.core.importing.domain.DedupKey;
import com.moneylog.core.importing.domain.DescriptionNormalizer;
import com.moneylog.core.importing.domain.ExistingEntry;
import com.moneylog.core.importing.domain.ImportBatch;
import com.moneylog.core.importing.domain.ImportBatchLine;
import com.moneylog.core.importing.domain.ImportLine;
import com.moneylog.core.importing.domain.ImportLineFlag;
import com.moneylog.core.importing.domain.LineFlagger;
import com.moneylog.core.importing.domain.TransferPairMatcher;
import com.moneylog.core.importing.exception.ImportingExceptions.ImportBatchNotFoundException;
import com.moneylog.core.importing.exception.ImportingExceptions.ImportFormatUnsupportedException;
import com.moneylog.core.importing.exception.ImportingExceptions.ImportIdempotencyKeyReusedException;
import com.moneylog.core.importing.exception.ImportingExceptions.ImportSourceNotFoundException;
import com.moneylog.core.importing.exception.ImportingExceptions.ImportSourceRequiredException;
import com.moneylog.core.importing.parser.ColumnMapping;
import com.moneylog.core.importing.parser.CsvStatementParser;
import com.moneylog.core.importing.parser.OfxStatementParser;
import com.moneylog.core.importing.parser.ParsedStatement;
import com.moneylog.core.sk.id.IdGenerator;
import com.moneylog.core.sk.time.TimeProvider;

/** Upload do extrato (T124): parse, normalização, deduplicação, marcas e sugestões; nada vira movimentação aqui. */
public class CreateImportBatchUseCaseImpl implements CreateImportBatchUseCase {

    public static final Duration IDEMPOTENCY_VALIDITY = Duration.ofHours(24);
    /** Linhas de histórico lidas para as sugestões. */
    static final int HISTORY_LIMIT = 5000;
    private static final int MAX_SKIPPED_LISTED = 200;

    private final ImportBatchGateway batches;
    private final ExistingEntriesGateway existing;
    private final ImportHistoryGateway history;
    private final ImportSourceGateway sources;
    private final ImportIdempotencyGateway idempotency;
    private final IdGenerator ids;
    private final TimeProvider time;

    @SuppressWarnings("java:S107")
    public CreateImportBatchUseCaseImpl(ImportBatchGateway batches, ExistingEntriesGateway existing, ImportHistoryGateway history,
                                        ImportSourceGateway sources, ImportIdempotencyGateway idempotency, IdGenerator ids, TimeProvider time) {
        this.batches = batches;
        this.existing = existing;
        this.history = history;
        this.sources = sources;
        this.idempotency = idempotency;
        this.ids = ids;
        this.time = time;
    }

    @Override
    public ImportBatchView handle(CreateImportBatchCommand command) {
        if ((command.accountId() == null) == (command.cardId() == null)) {
            throw new ImportSourceRequiredException();
        }
        if (command.content() == null || command.content().length == 0) {
            throw new com.moneylog.core.importing.exception.ImportingExceptions.ImportParseFailedException(0, "o arquivo está vazio.");
        }
        if (command.content().length > CsvStatementParser.MAX_BYTES) {
            throw new com.moneylog.core.importing.exception.ImportingExceptions.ImportTooLargeException("o limite é 5 MB.");
        }
        boolean card = command.cardId() != null;
        UUID sourceId = card ? command.cardId() : command.accountId();
        boolean found = card ? sources.cardExists(command.userId(), sourceId) : sources.accountExists(command.userId(), sourceId);
        if (!found) {
            throw new ImportSourceNotFoundException(card, sourceId);
        }
        String sha = sha256(command.content());
        UUID batchId = ids.next();
        if (command.idempotencyKey() != null) {
            Optional<ImportBatchView> replay = claimOrReplay(command, sourceId, sha, batchId);
            if (replay.isPresent()) {
                return replay.get();
            }
        }
        ParsedStatement statement = parse(command, sourceId);
        List<String> warnings = new ArrayList<>();
        if (batches.findSameFile(command.userId(), sourceId, sha).isPresent()) {
            warnings.add(ImportBatch.WARNING_SAME_FILE);
        }
        if (statement.cardStatement() != card && statement.format() == ParsedStatement.Format.OFX) {
            warnings.add("STATEMENT_KIND_MISMATCH");
        }
        List<ImportLine> lines = statement.rows().stream()
                .map(r -> new ImportLine(sourceId, r.date(), r.amount(), r.direction(), r.description(), r.fitId())).toList();
        List<ImportBatchLine> built = classify(command.userId(), batchId, sourceId, card, lines);
        List<String> skipped = statement.skipped().stream().limit(MAX_SKIPPED_LISTED)
                .map(s -> s.lineNumber() + ": " + s.reason()).toList();
        ImportBatch batch = ImportBatch.open(batchId, command.userId(), command.accountId(), command.cardId(),
                statement.format().name(), sha, time.now(), warnings, skipped);
        batches.insert(batch, built);
        return new ImportBatchView(batch, batches.counts(command.userId(), batchId));
    }

    private ParsedStatement parse(CreateImportBatchCommand command, UUID sourceId) {
        byte[] content = command.content();
        String format = command.format() == null ? detect(content) : command.format().toUpperCase(Locale.ROOT);
        return switch (format) {
            case "OFX" -> OfxStatementParser.parse(content);
            case "CSV" -> {
                CsvStatementParser.requireTextual(content);
                ColumnMapping mapping = command.mapping() != null ? command.mapping()
                        : history.findMapping(command.userId(), sourceId).orElse(null);
                ParsedStatement parsed = CsvStatementParser.parse(content, mapping);
                if (command.mapping() != null) {
                    history.saveMapping(command.userId(), sourceId, command.mapping());
                }
                yield parsed;
            }
            default -> throw new ImportFormatUnsupportedException("use CSV ou OFX (XLSX e PDF ainda não são suportados).");
        };
    }

    static String detect(byte[] content) {
        String head = new String(content, 0, Math.min(content.length, 1024), StandardCharsets.ISO_8859_1).toUpperCase(Locale.ROOT);
        if (head.contains("OFXHEADER") || head.contains("<OFX>") || head.contains("<?OFX")) {
            return "OFX";
        }
        if (content.length > 4 && content[0] == 'P' && content[1] == 'K' || head.startsWith("%PDF")) {
            throw new ImportFormatUnsupportedException("XLSX e PDF ainda não são suportados; envie CSV ou OFX.");
        }
        return "CSV";
    }

    private List<ImportBatchLine> classify(UUID userId, UUID batchId, UUID sourceId, boolean card, List<ImportLine> lines) {
        if (lines.isEmpty()) {
            return List.of();
        }
        LocalDate min = lines.stream().map(ImportLine::date).min(LocalDate::compareTo).orElseThrow();
        LocalDate max = lines.stream().map(ImportLine::date).max(LocalDate::compareTo).orElseThrow();
        Collection<ExistingEntry> sameSource = existing.findForSource(userId, sourceId, card, min.minusDays(1), max.plusDays(1));
        Collection<ExistingEntry> others = card ? List.of() : existing.findInOtherAccounts(userId, sourceId, min.minusDays(1), max.plusDays(1));
        List<DedupKey> keys = DedupKey.forFile(lines);
        CategorySuggester suggester = new CategorySuggester(
                history.recentCategorized(userId, HISTORY_LIMIT).stream().map(h -> h.toEntry(DescriptionNormalizer.normalize(h.description()))).toList(),
                history.rules(userId));
        List<ImportBatchLine> result = new ArrayList<>(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            ImportLine line = lines.get(i);
            Set<ImportLineFlag> flags = LineFlagger.flags(line, card, !card && TransferPairMatcher.hasOppositeLeg(line, others));
            Suggestion suggestion = suggester.suggest(line.direction(), line.normalizedDescription()).orElse(null);
            result.add(ImportBatchLine.open(ids.next(), batchId, i + 1, line, keys.get(i), sameSource, flags, suggestion));
        }
        return result;
    }

    private Optional<ImportBatchView> claimOrReplay(CreateImportBatchCommand command, UUID sourceId, String sha, UUID newBatchId) {
        String hash = sha256((command.accountId() + "|" + command.cardId() + "|" + sha + "|" + command.format()).getBytes(StandardCharsets.UTF_8));
        Instant now = time.now();
        for (int attempt = 0; attempt < 3; attempt++) {
            if (idempotency.claim(command.userId(), IDEMPOTENCY_SCOPE, command.idempotencyKey(), hash, newBatchId, now,
                    now.minus(IDEMPOTENCY_VALIDITY))) {
                return Optional.empty();
            }
            var claim = idempotency.find(command.userId(), IDEMPOTENCY_SCOPE, command.idempotencyKey());
            if (claim.isPresent()) {
                if (!claim.get().requestHash().equals(hash)) {
                    throw new ImportIdempotencyKeyReusedException();
                }
                ImportBatch batch = batches.find(command.userId(), claim.get().resourceId())
                        .orElseThrow(() -> new ImportBatchNotFoundException(claim.get().resourceId()));
                return Optional.of(new ImportBatchView(batch, batches.counts(command.userId(), batch.id())));
            }
        }
        throw new IllegalStateException("não foi possível reivindicar nem ler a chave de idempotência");
    }

    static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponível na JVM", e);
        }
    }
}
