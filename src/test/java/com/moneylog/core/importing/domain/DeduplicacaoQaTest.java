package com.moneylog.core.importing.domain;

import static com.moneylog.core.importing.util.factory.ImportTestFactory.BATCH_ID;
import static com.moneylog.core.importing.util.factory.ImportTestFactory.NUBANK_ID;
import static com.moneylog.core.importing.util.factory.ImportTestFactory.umaLinha;
import static com.moneylog.core.importing.util.factory.ImportTestFactory.umaLinhaDeCafe;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.moneylog.core.sk.money.Money;

@DisplayName("Importação: deduplicação (T12, QA)")
class DeduplicacaoQaTest {

    private static final UUID TX_A = UUID.fromString("00000000-0000-0000-0000-0000000cc001");
    private static final UUID TX_B = UUID.fromString("00000000-0000-0000-0000-0000000cc002");

    private static List<ExistingEntry> importar(List<ImportLine> lines, List<ExistingEntry> existing, String prefix) {
        List<DedupKey> keys = DedupKey.forFile(lines);
        List<ExistingEntry> created = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            ImportLineReview review = ImportLineReview.classify(lines.get(i), keys.get(i), existing);
            if (review.status() == ImportLineStatus.PENDING) {
                review.accept();
                ImportLine l = lines.get(i);
                created.add(new ExistingEntry(UUID.fromString(prefix + String.format("%04d", i)), l.sourceId(), l.date(), l.amount(),
                        l.direction(), review.effectiveKey(), review.key(), false, true));
            }
        }
        return created;
    }

    @Test
    @DisplayName("extrato estendido: reimportar [café, café] + [café, café, café] cria só o 3º café")
    void deveImportarSoALinhaNovaDoExtratoEstendido() {
        List<ExistingEntry> base = importar(List.of(umaLinhaDeCafe(), umaLinhaDeCafe()), List.of(), "00000000-0000-0000-0000-0000000d");
        assertThat(base).hasSize(2);
        List<ExistingEntry> segunda = importar(List.of(umaLinhaDeCafe(), umaLinhaDeCafe(), umaLinhaDeCafe()), base,
                "00000000-0000-0000-0000-0000000e");
        assertThat(segunda).hasSize(1);
        List<ExistingEntry> todas = new ArrayList<>(base);
        todas.addAll(segunda);
        assertThat(importar(List.of(umaLinhaDeCafe(), umaLinhaDeCafe(), umaLinhaDeCafe()), todas, "00000000-0000-0000-0000-0000000f"))
                .as("terceira importação idempotente").isEmpty();
    }

    @ParameterizedTest(name = "manual em {0} × linha em 2026-10-10 → {1}")
    @CsvSource({"2026-10-09, POSSIBLE_DUPLICATE", "2026-10-11, POSSIBLE_DUPLICATE", "2026-10-08, PENDING", "2026-10-12, PENDING"})
    @DisplayName("janela de possível duplicata é exatamente ±1 dia (S-25)")
    void deveRespeitarAJanela(String manualDate, ImportLineStatus expected) {
        ExistingEntry manual = new ExistingEntry(TX_A, NUBANK_ID, LocalDate.parse(manualDate), Money.of("8.50"), ImportDirection.OUT, null,
                null, true, true);
        ImportLine line = umaLinhaDeCafe();
        assertThat(DuplicateClassifier.classify(line, DedupKey.of(line, 1), List.of(manual)).status()).isEqualTo(expected);
    }

    @Test
    @DisplayName("virada de ano: manual em 31/12 e linha em 01/01 são possível duplicata")
    void deveCruzarAViradaDeAno() {
        ExistingEntry manual = new ExistingEntry(TX_A, NUBANK_ID, LocalDate.of(2026, 12, 31), Money.of("8.50"), ImportDirection.OUT,
                null, null, true, true);
        ImportLine line = umaLinha(NUBANK_ID, LocalDate.of(2027, 1, 1), "8.50", ImportDirection.OUT, "Padaria");
        assertThat(DuplicateClassifier.classify(line, DedupKey.of(line, 1), List.of(manual)).status())
                .isEqualTo(ImportLineStatus.POSSIBLE_DUPLICATE);
    }

    @Test
    @DisplayName("29/02 bissexto e 01/03 são datas distintas na chave; mesma linha em 29/02 reimportada é DUPLICATE")
    void deveTratarDataBissexta() {
        ImportLine bissexto = umaLinha(NUBANK_ID, LocalDate.of(2028, 2, 29), "10.00", ImportDirection.OUT, "x");
        ImportLine marco = umaLinha(NUBANK_ID, LocalDate.of(2028, 3, 1), "10.00", ImportDirection.OUT, "x");
        assertThat(DedupKey.of(bissexto, 1)).isNotEqualTo(DedupKey.of(marco, 1));
        ExistingEntry gravada = new ExistingEntry(TX_A, NUBANK_ID, bissexto.date(), bissexto.amount(), bissexto.direction(),
                DedupKey.of(bissexto, 1), DedupKey.of(bissexto, 1), false, true);
        assertThat(DuplicateClassifier.classify(bissexto, DedupKey.of(bissexto, 1), List.of(gravada)).status())
                .isEqualTo(ImportLineStatus.DUPLICATE);
    }

    @Test
    @DisplayName("descrição nula, vazia e só espaços/pontuação geram a mesma chave")
    void deveNormalizarDescricaoVazia() {
        DedupKey nula = DedupKey.of(umaLinha(NUBANK_ID, LocalDate.of(2026, 10, 10), "1.00", ImportDirection.OUT, null), 1);
        assertThat(DedupKey.of(umaLinha(NUBANK_ID, LocalDate.of(2026, 10, 10), "1.00", ImportDirection.OUT, ""), 1)).isEqualTo(nula);
        assertThat(DedupKey.of(umaLinha(NUBANK_ID, LocalDate.of(2026, 10, 10), "1.00", ImportDirection.OUT, " *** - "), 1)).isEqualTo(nula);
    }

    @Test
    @DisplayName("BUG-QA-04: depois de forçar uma duplicata e estornar a original, reimportar não pode criar outra cópia")
    void naoDeveDuplicarDepoisDeForcarEEstornar() {
        ImportLine cafe = umaLinhaDeCafe();
        DedupKey key = DedupKey.of(cafe, 1);
        ExistingEntry original = new ExistingEntry(TX_A, NUBANK_ID, cafe.date(), cafe.amount(), cafe.direction(), key, key, false, true);
        ImportLineReview forced = ImportLineReview.classify(cafe, key, List.of(original));
        forced.forceImport(BATCH_ID);
        ExistingEntry forcada = new ExistingEntry(TX_B, NUBANK_ID, cafe.date(), cafe.amount(), cafe.direction(), forced.effectiveKey(),
                forced.key(), false, true);
        ExistingEntry originalEstornada = new ExistingEntry(TX_A, NUBANK_ID, cafe.date(), cafe.amount(), cafe.direction(), key, key, false,
                false);

        assertThat(forced.effectiveKey()).isNotEqualTo(key);
        DuplicateClassifier.Classification reimportacao = DuplicateClassifier.classify(cafe, key, List.of(originalEstornada, forcada));
        assertThat(reimportacao.status()).isEqualTo(ImportLineStatus.DUPLICATE);
        assertThat(reimportacao.matches()).containsExactly(TX_B);
    }

    @Test
    @DisplayName("T47 CA2: movimentação forçada estornada não é duplicata pela chave efetiva nem pela original")
    void naoDeveContarForcadaEstornada() {
        ImportLine cafe = umaLinhaDeCafe();
        DedupKey key = DedupKey.of(cafe, 1);
        DedupKey derivada = key.forcedIn(BATCH_ID);
        ExistingEntry forcadaEstornada = new ExistingEntry(TX_B, NUBANK_ID, cafe.date(), cafe.amount(), cafe.direction(), derivada, key,
                false, false);
        ExistingEntry forcadaAtiva = new ExistingEntry(TX_B, NUBANK_ID, cafe.date(), cafe.amount(), cafe.direction(), derivada, key,
                false, true);

        assertThat(DuplicateClassifier.classify(cafe, key, List.of(forcadaEstornada)).status()).isEqualTo(ImportLineStatus.PENDING);
        assertThat(DuplicateClassifier.classify(cafe, derivada, List.of(forcadaEstornada)).status()).isEqualTo(ImportLineStatus.PENDING);
        assertThat(DuplicateClassifier.classify(cafe, key, List.of(forcadaAtiva)).status()).isEqualTo(ImportLineStatus.DUPLICATE);
        assertThat(DuplicateClassifier.classify(cafe, derivada, List.of(forcadaAtiva)).status()).isEqualTo(ImportLineStatus.DUPLICATE);
    }

    @Test
    @DisplayName("T47 CA3: a mesma linha forçada duas vezes no mesmo lote gera a mesma chave derivada (barrada); em outro lote, não")
    void deveBarrarAMesmaLinhaForcadaNoMesmoLote() {
        ImportLine cafe = umaLinhaDeCafe();
        DedupKey key = DedupKey.of(cafe, 1);
        ExistingEntry original = new ExistingEntry(TX_A, NUBANK_ID, cafe.date(), cafe.amount(), cafe.direction(), key, key, false, true);

        ImportLineReview primeira = ImportLineReview.classify(cafe, key, List.of(original));
        primeira.forceImport(BATCH_ID);
        ExistingEntry forcada = new ExistingEntry(TX_B, NUBANK_ID, cafe.date(), cafe.amount(), cafe.direction(), primeira.effectiveKey(),
                primeira.key(), false, true);

        ImportLineReview retry = ImportLineReview.classify(cafe, key, List.of(original, forcada));
        retry.forceImport(BATCH_ID);
        ImportLineReview outroLote = ImportLineReview.classify(cafe, key, List.of(original, forcada));
        outroLote.forceImport(UUID.fromString("00000000-0000-0000-0000-0000000cc0ff"));

        assertThat(retry.effectiveKey()).isEqualTo(primeira.effectiveKey());
        assertThat(forcada.hasKey(retry.effectiveKey())).isTrue();
        assertThat(DuplicateClassifier.classify(cafe, retry.effectiveKey(), List.of(forcada)).status())
                .isEqualTo(ImportLineStatus.DUPLICATE);
        assertThat(outroLote.effectiveKey()).isNotEqualTo(primeira.effectiveKey());
        assertThat(forcada.hasKey(outroLote.effectiveKey())).isFalse();
    }
}
