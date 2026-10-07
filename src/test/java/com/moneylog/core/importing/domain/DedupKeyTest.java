package com.moneylog.core.importing.domain;

import static com.moneylog.core.importing.util.factory.ImportTestFactory.BATCH_ID;
import static com.moneylog.core.importing.util.factory.ImportTestFactory.DATE;
import static com.moneylog.core.importing.util.factory.ImportTestFactory.NUBANK_ID;
import static com.moneylog.core.importing.util.factory.ImportTestFactory.OTHER_BATCH_ID;
import static com.moneylog.core.importing.util.factory.ImportTestFactory.XP_ID;
import static com.moneylog.core.importing.util.factory.ImportTestFactory.umaLinha;
import static com.moneylog.core.importing.util.factory.ImportTestFactory.umaLinhaDeCafe;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.moneylog.core.importing.exception.ImportingExceptions.InvalidImportLineException;
import com.moneylog.core.sk.money.Money;

@DisplayName("DedupKey")
class DedupKeyTest {

    @Test
    @DisplayName("duas linhas idênticas no mesmo arquivo recebem chaves diferentes (ordinais 1 e 2)")
    void linhasIdenticasTemChavesDiferentes() {
        List<DedupKey> chaves = DedupKey.forFile(List.of(umaLinhaDeCafe(), umaLinhaDeCafe()));

        assertThat(chaves).hasSize(2).doesNotHaveDuplicates();
        assertThat(chaves.get(0)).isEqualTo(DedupKey.of(umaLinhaDeCafe(), 1));
        assertThat(chaves.get(1)).isEqualTo(DedupKey.of(umaLinhaDeCafe(), 2));
    }

    @Test
    @DisplayName("reprocessar o mesmo arquivo repete as mesmas chaves (determinístico)")
    void reprocessarRepeteAsChaves() {
        List<ImportLine> arquivo = List.of(
                umaLinhaDeCafe(),
                umaLinha(NUBANK_ID, DATE, "120.00", ImportDirection.OUT, "Mercado"),
                umaLinhaDeCafe());

        assertThat(DedupKey.forFile(arquivo)).isEqualTo(DedupKey.forFile(List.copyOf(arquivo)));
        assertThat(DedupKey.forFile(arquivo).get(2)).isEqualTo(DedupKey.of(umaLinhaDeCafe(), 2));
    }

    @Test
    @DisplayName("descrições que só diferem em acento, caixa ou pontuação geram a mesma chave")
    void descricaoNormalizadaNaChave() {
        ImportLine original = umaLinha(NUBANK_ID, DATE, "8.50", ImportDirection.OUT, "Padaria Pão & Café");
        ImportLine variante = umaLinha(NUBANK_ID, DATE, "8.50", ImportDirection.OUT, "  PADARIA pao - cafe ");

        assertThat(DedupKey.of(variante, 1)).isEqualTo(DedupKey.of(original, 1));
    }

    static Stream<Arguments> linhasQueDiferem() {
        ImportLine base = umaLinhaDeCafe();
        return Stream.of(
                Arguments.of("valor", umaLinha(NUBANK_ID, DATE, "8.51", ImportDirection.OUT, base.description())),
                Arguments.of("data", umaLinha(NUBANK_ID, DATE.plusDays(1), "8.50", ImportDirection.OUT, base.description())),
                Arguments.of("direção", umaLinha(NUBANK_ID, DATE, "8.50", ImportDirection.IN, base.description())),
                Arguments.of("conta", umaLinha(XP_ID, DATE, "8.50", ImportDirection.OUT, base.description())),
                Arguments.of("descrição", umaLinha(NUBANK_ID, DATE, "8.50", ImportDirection.OUT, "Padaria Pão e Café")));
    }

    @ParameterizedTest(name = "diferença em {0}")
    @MethodSource("linhasQueDiferem")
    @DisplayName("linhas que diferem em valor, data, direção, conta ou descrição têm chaves diferentes")
    void linhasDiferentesTemChavesDiferentes(String campo, ImportLine outra) {
        assertThat(DedupKey.of(outra, 1)).isNotEqualTo(DedupKey.of(umaLinhaDeCafe(), 1));
    }

    @Test
    @DisplayName("o FITID do OFX substitui a descrição na chave")
    void fitIdSubstituiADescricao() {
        ImportLine comFitId = new ImportLine(NUBANK_ID, DATE, Money.of("8.50"), ImportDirection.OUT, "Padaria", "2026101000123");
        ImportLine mesmoFitIdOutraDescricao = new ImportLine(NUBANK_ID, DATE, Money.of("8.50"), ImportDirection.OUT, "PADARIA CENTRO LTDA", " 2026101000123 ");
        ImportLine outroFitId = new ImportLine(NUBANK_ID, DATE, Money.of("8.50"), ImportDirection.OUT, "Padaria", "2026101000124");
        ImportLine semFitId = ImportLine.of(NUBANK_ID, DATE, Money.of("8.50"), ImportDirection.OUT, "Padaria");

        assertThat(DedupKey.of(mesmoFitIdOutraDescricao, 1)).isEqualTo(DedupKey.of(comFitId, 1));
        assertThat(DedupKey.of(outroFitId, 1)).isNotEqualTo(DedupKey.of(comFitId, 1));
        assertThat(DedupKey.of(semFitId, 1)).isNotEqualTo(DedupKey.of(comFitId, 1));
    }

    @Test
    @DisplayName("FITIDs diferentes não são linhas idênticas: ambas ficam com ordinal 1")
    void fitIdsDiferentesNaoContamComoIdenticas() {
        ImportLine a = new ImportLine(NUBANK_ID, DATE, Money.of("8.50"), ImportDirection.OUT, "Café", "A1");
        ImportLine b = new ImportLine(NUBANK_ID, DATE, Money.of("8.50"), ImportDirection.OUT, "Café", "A2");

        assertThat(DedupKey.forFile(List.of(a, b))).containsExactly(DedupKey.of(a, 1), DedupKey.of(b, 1));
    }

    @Test
    @DisplayName("a chave é SHA-256 em hex (64 caracteres) e o ordinal começa em 1")
    void formatoDaChave() {
        DedupKey chave = DedupKey.of(umaLinhaDeCafe(), 1);

        assertThat(chave.value()).matches("[0-9a-f]{64}");
        assertThat(chave).hasToString(chave.value());
        assertThatThrownBy(() -> DedupKey.of(umaLinhaDeCafe(), 0)).isInstanceOf(InvalidImportLineException.class);
        assertThatThrownBy(() -> new DedupKey("abc")).isInstanceOf(InvalidImportLineException.class);
        assertThatThrownBy(() -> new DedupKey(null)).isInstanceOf(InvalidImportLineException.class);
    }

    @Test
    @DisplayName("a chave forçada é derivada do lote: difere da original e de outro lote, e é determinística")
    void chaveForcada() {
        DedupKey original = DedupKey.of(umaLinhaDeCafe(), 1);

        assertThat(original.forcedIn(BATCH_ID))
                .isNotEqualTo(original)
                .isEqualTo(original.forcedIn(BATCH_ID))
                .isNotEqualTo(original.forcedIn(OTHER_BATCH_ID));
    }
}
