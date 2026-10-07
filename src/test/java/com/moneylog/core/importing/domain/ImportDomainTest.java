package com.moneylog.core.importing.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.moneylog.core.importing.domain.CategorySuggester.Confidence;
import com.moneylog.core.importing.domain.CategorySuggester.HistoryEntry;
import com.moneylog.core.importing.domain.CategorySuggester.Rule;
import com.moneylog.core.importing.domain.CategorySuggester.Suggestion;
import com.moneylog.core.importing.exception.ImportingExceptions.ImportBatchNotPendingException;
import com.moneylog.core.importing.exception.ImportingExceptions.ImportBatchNotUndoableException;
import com.moneylog.core.importing.exception.ImportingExceptions.ImportSourceRequiredException;
import com.moneylog.core.importing.exception.ImportingExceptions.InvalidImportLineException;
import com.moneylog.core.importing.exception.ImportingExceptions.InvalidImportLineTransitionException;
import com.moneylog.core.sk.money.Money;

@DisplayName("Domínio da importação (Etapa 7)")
class ImportDomainTest {

    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID NUBANK = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID XP = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
    private static final UUID CARD = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    private static final UUID BATCH = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final UUID FOOD = UUID.fromString("00000000-0000-0000-0000-0000000000f1");
    private static final UUID FUEL = UUID.fromString("00000000-0000-0000-0000-0000000000f2");
    private static final LocalDate D = LocalDate.of(2026, 10, 10);

    private static ImportLine line(UUID source, String amount, ImportDirection direction, String description) {
        return ImportLine.of(source, D, Money.of(amount), direction, description);
    }

    private static ImportBatchLine open(ImportLine line, Set<ImportLineFlag> flags, Suggestion suggestion, List<ExistingEntry> existing) {
        return ImportBatchLine.open(UUID.randomUUID(), BATCH, 1, line, DedupKey.of(line, 1), existing, flags, suggestion);
    }

    @Nested
    @DisplayName("LineFlagger (S-167, S-168)")
    class Flagger {
        @Test
        @DisplayName("cartão: pagamento de fatura é marcado; não é marcado em extrato de conta")
        void pagamentoDeFatura() {
            ImportLine payment = line(CARD, "1283.00", ImportDirection.IN, "PAGAMENTO RECEBIDO");
            assertThat(LineFlagger.flags(payment, true, false)).containsExactly(ImportLineFlag.LOOKS_LIKE_INVOICE_PAYMENT);
            assertThat(LineFlagger.flags(line(NUBANK, "1283.00", ImportDirection.OUT, "Pagto Fatura Nubank"), false, false)).isEmpty();
            assertThat(LineFlagger.flags(line(CARD, "10.00", ImportDirection.OUT, "Pagto fatura"), true, false))
                    .containsExactly(ImportLineFlag.LOOKS_LIKE_INVOICE_PAYMENT);
        }

        @Test
        @DisplayName("cartão: parcela exige decisão (PARC 2/6, 02/06 no fim); 05/11 sem PARC e valores normais não")
        void parcela() {
            assertThat(LineFlagger.flags(line(CARD, "200.00", ImportDirection.OUT, "PARC 2/6 NOTEBOOK"), true, false))
                    .containsExactly(ImportLineFlag.LOOKS_LIKE_INSTALLMENT);
            assertThat(LineFlagger.flags(line(CARD, "200.00", ImportDirection.OUT, "NOTEBOOK 02/06"), true, false))
                    .containsExactly(ImportLineFlag.LOOKS_LIKE_INSTALLMENT);
            assertThat(LineFlagger.flags(line(CARD, "200.00", ImportDirection.OUT, "Mercado Central"), true, false)).isEmpty();
            assertThat(LineFlagger.flags(line(CARD, "200.00", ImportDirection.OUT, "PARC 9/6 inválida"), true, false)).isEmpty();
        }

        @Test
        @DisplayName("cartão: crédito que não é pagamento (estorno) nunca vira compra")
        void creditoNoCartao() {
            assertThat(LineFlagger.flags(line(CARD, "30.00", ImportDirection.IN, "ESTORNO LOJA X"), true, false))
                    .containsExactly(ImportLineFlag.CARD_CREDIT_NOT_IMPORTED);
        }

        @Test
        @DisplayName("conta: a perna oposta em outra conta marca POSSIBLE_TRANSFER")
        void transferencia() {
            ImportLine out = line(NUBANK, "1000.00", ImportDirection.OUT, "TED");
            assertThat(LineFlagger.flags(out, false, true)).containsExactly(ImportLineFlag.POSSIBLE_TRANSFER);
            assertThat(LineFlagger.flags(out, false, false)).isEmpty();
        }
    }

    @Nested
    @DisplayName("TransferPairMatcher")
    class Pair {
        private ExistingEntry entry(UUID source, String amount, ImportDirection direction, LocalDate date) {
            return new ExistingEntry(UUID.randomUUID(), source, date, Money.of(amount), direction, null, null, false, true);
        }

        @Test
        @DisplayName("mesmo valor, direção oposta, ±1 dia, outra conta: casa; fora disso não")
        void casa() {
            ImportLine out = line(NUBANK, "1000.00", ImportDirection.OUT, "TED");
            assertThat(TransferPairMatcher.hasOppositeLeg(out, List.of(entry(XP, "1000.00", ImportDirection.IN, D.plusDays(1))))).isTrue();
            assertThat(TransferPairMatcher.hasOppositeLeg(out, List.of(entry(XP, "1000.00", ImportDirection.IN, D.plusDays(2))))).isFalse();
            assertThat(TransferPairMatcher.hasOppositeLeg(out, List.of(entry(XP, "1000.00", ImportDirection.OUT, D)))).isFalse();
            assertThat(TransferPairMatcher.hasOppositeLeg(out, List.of(entry(XP, "999.99", ImportDirection.IN, D)))).isFalse();
            assertThat(TransferPairMatcher.hasOppositeLeg(out, List.of(entry(NUBANK, "1000.00", ImportDirection.IN, D)))).isFalse();
        }
    }

    @Nested
    @DisplayName("CategorySuggester (S-172)")
    class Suggester {
        private List<HistoryEntry> history(int count, UUID category) {
            return java.util.Collections.nCopies(count, new HistoryEntry(ImportDirection.OUT, "POSTO SHELL", category, "DEBIT"));
        }

        @Test
        @DisplayName("3 ocorrências na mesma categoria = HIGH; 2 = MEDIUM; 1 = sem sugestão")
        void confianca() {
            assertThat(new CategorySuggester(history(3, FUEL), List.of()).suggest(ImportDirection.OUT, "POSTO SHELL"))
                    .contains(new Suggestion(FUEL, "DEBIT", Confidence.HIGH));
            assertThat(new CategorySuggester(history(2, FUEL), List.of()).suggest(ImportDirection.OUT, "POSTO SHELL").orElseThrow().confidence())
                    .isEqualTo(Confidence.MEDIUM);
            assertThat(new CategorySuggester(history(1, FUEL), List.of()).suggest(ImportDirection.OUT, "POSTO SHELL")).isEmpty();
        }

        @Test
        @DisplayName("igualdade da descrição normalizada: 'POSTO SHELL 1234' não casa com 'POSTO SHELL' (limite documentado)")
        void igualdadeExata() {
            assertThat(new CategorySuggester(history(3, FUEL), List.of()).suggest(ImportDirection.OUT, "POSTO SHELL 1234")).isEmpty();
        }

        @Test
        @DisplayName("a direção importa: despesa não sugere categoria para receita")
        void direcao() {
            assertThat(new CategorySuggester(history(3, FUEL), List.of()).suggest(ImportDirection.IN, "POSTO SHELL")).isEmpty();
        }

        @Test
        @DisplayName("categorias divididas: 2 de 3 abaixo de 80% = MEDIUM com a mais frequente; empate resolve de forma determinística")
        void dividido() {
            List<HistoryEntry> mixed = new java.util.ArrayList<>(history(2, FUEL));
            mixed.addAll(history(1, FOOD));
            Suggestion suggestion = new CategorySuggester(mixed, List.of()).suggest(ImportDirection.OUT, "POSTO SHELL").orElseThrow();
            assertThat(suggestion.categoryId()).isEqualTo(FUEL);
            assertThat(suggestion.confidence()).isEqualTo(Confidence.MEDIUM);
            List<HistoryEntry> tie = new java.util.ArrayList<>(history(2, FUEL));
            tie.addAll(history(2, FOOD));
            assertThat(new CategorySuggester(tie, List.of()).suggest(ImportDirection.OUT, "POSTO SHELL").orElseThrow().categoryId())
                    .isEqualTo(new CategorySuggester(tie, List.of()).suggest(ImportDirection.OUT, "POSTO SHELL").orElseThrow().categoryId());
        }

        @Test
        @DisplayName("regra aprendida vale HIGH e vence o histórico")
        void regra() {
            CategorySuggester suggester = new CategorySuggester(history(3, FUEL), List.of(new Rule(ImportDirection.OUT, "POSTO SHELL", FOOD, "PIX")));
            assertThat(suggester.suggest(ImportDirection.OUT, "POSTO SHELL")).contains(new Suggestion(FOOD, "PIX", Confidence.HIGH));
        }
    }

    @Nested
    @DisplayName("ImportBatchLine: transições")
    class Lines {
        private final Suggestion suggestion = new Suggestion(FOOD, "PIX", Confidence.HIGH);

        @Test
        @DisplayName("linha nova: aceitar usa a sugestão; estados finais não voltam")
        void aceitar() {
            ImportBatchLine l = open(line(NUBANK, "10.00", ImportDirection.OUT, "Padaria"), Set.of(), suggestion, List.of());
            assertThat(l.status()).isEqualTo(ImportLineStatus.PENDING);
            l.accept(null, null, null);
            assertThat(l.status()).isEqualTo(ImportLineStatus.ACCEPTED);
            assertThat(l.categoryId()).isEqualTo(FOOD);
            assertThat(l.method()).isEqualTo("PIX");
            assertThatThrownBy(() -> l.accept(null, null, null)).isInstanceOf(InvalidImportLineTransitionException.class);
        }

        @Test
        @DisplayName("aceitar sem categoria (e sem sugestão) é recusado")
        void semCategoria() {
            ImportBatchLine l = open(line(NUBANK, "10.00", ImportDirection.OUT, "Padaria"), Set.of(), null, List.of());
            assertThatThrownBy(() -> l.accept(null, null, null)).isInstanceOf(InvalidImportLineException.class);
            assertThat(l.status()).isEqualTo(ImportLineStatus.PENDING);
        }

        @Test
        @DisplayName("DUPLICATE: só FORCE ou REJECT; ACCEPT direto é transição inválida; forçar gera chave derivada do lote")
        void duplicata() {
            ImportLine imported = line(NUBANK, "10.00", ImportDirection.OUT, "Padaria");
            ExistingEntry same = new ExistingEntry(UUID.randomUUID(), NUBANK, D, Money.of("10.00"), ImportDirection.OUT, DedupKey.of(imported, 1), null,
                    false, true);
            ImportBatchLine l = open(imported, Set.of(), suggestion, List.of(same));
            assertThat(l.status()).isEqualTo(ImportLineStatus.DUPLICATE);
            assertThat(l.requiresDecision()).isFalse();
            assertThatThrownBy(() -> l.accept(null, null, null)).isInstanceOf(InvalidImportLineTransitionException.class);
            l.force(BATCH, null, null);
            assertThat(l.status()).isEqualTo(ImportLineStatus.ACCEPTED);
            assertThat(l.review().effectiveKey()).isNotEqualTo(l.review().key());
            assertThat(l.review().isForced()).isTrue();
        }

        @Test
        @DisplayName("FORCE em linha que não é DUPLICATE é recusado")
        void forcarNova() {
            ImportBatchLine l = open(line(NUBANK, "10.00", ImportDirection.OUT, "Padaria"), Set.of(), suggestion, List.of());
            assertThatThrownBy(() -> l.force(BATCH, null, null)).isInstanceOf(InvalidImportLineTransitionException.class);
        }

        @Test
        @DisplayName("POSSIBLE_DUPLICATE exige decisão; aceitar = outro lançamento, rejeitar = o mesmo")
        void possivelDuplicata() {
            ImportLine imported = line(NUBANK, "10.00", ImportDirection.OUT, "Padaria");
            ExistingEntry manual = new ExistingEntry(UUID.randomUUID(), NUBANK, D.minusDays(1), Money.of("10.00"), ImportDirection.OUT, null, null,
                    true, true);
            ImportBatchLine l = open(imported, Set.of(), suggestion, List.of(manual));
            assertThat(l.status()).isEqualTo(ImportLineStatus.POSSIBLE_DUPLICATE);
            assertThat(l.requiresDecision()).isTrue();
            assertThat(l.review().matches()).containsExactly(manual.transactionId());
            l.accept(null, null, null);
            assertThat(l.requiresDecision()).isFalse();
        }

        @Test
        @DisplayName("pagamento de fatura nasce REJECTED com o motivo; crédito no cartão também")
        void rejeitadasPorPadrao() {
            ImportBatchLine payment = open(line(CARD, "1283.00", ImportDirection.IN, "PAGAMENTO RECEBIDO"),
                    Set.of(ImportLineFlag.LOOKS_LIKE_INVOICE_PAYMENT), null, List.of());
            assertThat(payment.status()).isEqualTo(ImportLineStatus.REJECTED);
            assertThat(payment.rejectReason()).isEqualTo(ImportBatchLine.REASON_INVOICE_PAYMENT);
            ImportBatchLine credit = open(line(CARD, "30.00", ImportDirection.IN, "ESTORNO"), Set.of(ImportLineFlag.CARD_CREDIT_NOT_IMPORTED), null,
                    List.of());
            assertThat(credit.status()).isEqualTo(ImportLineStatus.REJECTED);
        }

        @Test
        @DisplayName("parcela e possível transferência exigem decisão até alguém decidir")
        void exigemDecisao() {
            ImportBatchLine installment = open(line(CARD, "200.00", ImportDirection.OUT, "PARC 2/6"), Set.of(ImportLineFlag.LOOKS_LIKE_INSTALLMENT),
                    suggestion, List.of());
            assertThat(installment.requiresDecision()).isTrue();
            installment.reject();
            assertThat(installment.requiresDecision()).isFalse();
            ImportBatchLine transfer = open(line(NUBANK, "1000.00", ImportDirection.OUT, "TED"), Set.of(ImportLineFlag.POSSIBLE_TRANSFER), suggestion,
                    List.of());
            assertThat(transfer.requiresDecision()).isTrue();
        }

        @Test
        @DisplayName("asTransfer: só em linha marcada POSSIBLE_TRANSFER e PENDING, com outra conta; vira ACCEPTED sem categoria")
        void comoTransferencia() {
            ImportBatchLine transfer = open(line(NUBANK, "1000.00", ImportDirection.OUT, "TED"), Set.of(ImportLineFlag.POSSIBLE_TRANSFER), suggestion,
                    List.of());
            assertThatThrownBy(() -> transfer.acceptAsTransfer(NUBANK)).isInstanceOf(InvalidImportLineException.class);
            transfer.acceptAsTransfer(XP);
            assertThat(transfer.isTransfer()).isTrue();
            assertThat(transfer.status()).isEqualTo(ImportLineStatus.ACCEPTED);
            assertThat(transfer.categoryId()).isNull();
            ImportBatchLine plain = open(line(NUBANK, "10.00", ImportDirection.OUT, "Padaria"), Set.of(), suggestion, List.of());
            assertThatThrownBy(() -> plain.acceptAsTransfer(XP)).isInstanceOf(InvalidImportLineTransitionException.class);
        }
    }

    @Nested
    @DisplayName("Desfazer a aceitação (A2)")
    class Reopen {
        private final Suggestion suggestion = new Suggestion(FOOD, "PIX", Confidence.HIGH);

        @Test
        @DisplayName("aceita volta a PENDING; transferência decidida volta sem contraparte e ainda exige decisão; rejeitada é final")
        void reabrir() {
            ImportBatchLine transfer = open(line(NUBANK, "1000.00", ImportDirection.OUT, "TED"), Set.of(ImportLineFlag.POSSIBLE_TRANSFER), suggestion,
                    List.of());
            transfer.acceptAsTransfer(XP);
            transfer.reopen();
            assertThat(transfer.status()).isEqualTo(ImportLineStatus.PENDING);
            assertThat(transfer.isTransfer()).isFalse();
            assertThat(transfer.requiresDecision()).isTrue();
            transfer.reject();
            assertThat(transfer.status()).isEqualTo(ImportLineStatus.REJECTED);
            assertThatThrownBy(transfer::reopen).isInstanceOf(InvalidImportLineTransitionException.class);
            assertThatThrownBy(transfer::reject).isInstanceOf(InvalidImportLineTransitionException.class);
        }

        @Test
        @DisplayName("possível duplicata aceita volta a POSSIBLE_DUPLICATE (a decisão não é pulada)")
        void possivelDuplicata() {
            ImportLine raw = line(NUBANK, "10.00", ImportDirection.OUT, "Padaria");
            ExistingEntry manual = new ExistingEntry(new UUID(2, 2), NUBANK, raw.date(), raw.amount(), raw.direction(), null, null, true, true);
            ImportBatchLine l = open(raw, Set.of(), suggestion, List.of(manual));
            assertThat(l.status()).isEqualTo(ImportLineStatus.POSSIBLE_DUPLICATE);
            l.accept(null, null, null);
            l.reopen();
            assertThat(l.status()).isEqualTo(ImportLineStatus.POSSIBLE_DUPLICATE);
            assertThat(l.requiresDecision()).isTrue();
        }

        @Test
        @DisplayName("duplicata forçada volta a DUPLICATE com a chave original; rejeitada depois também restaura a chave")
        void forcada() {
            ImportLine raw = line(NUBANK, "10.00", ImportDirection.OUT, "Padaria");
            ExistingEntry same = new ExistingEntry(new UUID(2, 2), NUBANK, raw.date(), raw.amount(), raw.direction(), DedupKey.of(raw, 1), null, false,
                    true);
            ImportBatchLine l = open(raw, Set.of(), suggestion, List.of(same));
            l.force(BATCH, null, null);
            assertThat(l.review().effectiveKey()).isNotEqualTo(l.review().key());
            l.reopen();
            assertThat(l.status()).isEqualTo(ImportLineStatus.DUPLICATE);
            assertThat(l.review().effectiveKey()).isEqualTo(l.review().key());
        }
    }

    @Nested
    @DisplayName("ImportBatch")
    class Batch {
        private ImportBatch open(UUID account, UUID card) {
            return ImportBatch.open(BATCH, USER, account, card, "CSV", "a".repeat(64), Instant.parse("2026-10-10T12:00:00Z"), List.of(), List.of());
        }

        @Test
        @DisplayName("exatamente uma fonte: conta XOR cartão")
        void fonte() {
            assertThatThrownBy(() -> open(NUBANK, CARD)).isInstanceOf(ImportSourceRequiredException.class);
            assertThatThrownBy(() -> open(null, null)).isInstanceOf(ImportSourceRequiredException.class);
            assertThat(open(null, CARD).isCard()).isTrue();
            assertThat(open(NUBANK, null).sourceId()).isEqualTo(NUBANK);
        }

        @Test
        @DisplayName("PENDING_REVIEW → APPLIED → UNDONE/PARTIALLY_UNDONE; aplicar duas vezes e desfazer sem aplicar são recusados")
        void ciclo() {
            ImportBatch batch = open(NUBANK, null);
            assertThatThrownBy(() -> batch.markUndone(Instant.now(), null, true)).isInstanceOf(ImportBatchNotUndoableException.class);
            batch.markApplied(Instant.parse("2026-10-10T13:00:00Z"));
            assertThat(batch.status()).isEqualTo(ImportBatchStatus.APPLIED);
            assertThatThrownBy(() -> batch.markApplied(Instant.now())).isInstanceOf(ImportBatchNotPendingException.class);
            assertThatThrownBy(batch::ensurePendingReview).isInstanceOf(ImportBatchNotPendingException.class);
            batch.markUndone(Instant.parse("2026-10-10T14:00:00Z"), "engano", false);
            assertThat(batch.status()).isEqualTo(ImportBatchStatus.PARTIALLY_UNDONE);
            batch.markUndone(Instant.parse("2026-10-10T15:00:00Z"), "engano", true);
            assertThat(batch.status()).isEqualTo(ImportBatchStatus.UNDONE);
            assertThatThrownBy(() -> batch.markUndone(Instant.now(), null, true)).isInstanceOf(ImportBatchNotUndoableException.class);
        }
    }
}
