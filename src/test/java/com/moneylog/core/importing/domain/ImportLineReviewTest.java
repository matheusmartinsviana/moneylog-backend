package com.moneylog.core.importing.domain;

import static com.moneylog.core.importing.util.factory.ImportTestFactory.BATCH_ID;
import static com.moneylog.core.importing.util.factory.ImportTestFactory.DATE;
import static com.moneylog.core.importing.util.factory.ImportTestFactory.NUBANK_ID;
import static com.moneylog.core.importing.util.factory.ImportTestFactory.TRANSACTION_ID;
import static com.moneylog.core.importing.util.factory.ImportTestFactory.XP_ID;
import static com.moneylog.core.importing.util.factory.ImportTestFactory.umaLinhaDeCafe;
import static com.moneylog.core.importing.util.factory.ImportTestFactory.umaMovimentacaoImportada;
import static com.moneylog.core.importing.util.factory.ImportTestFactory.umaMovimentacaoManual;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import com.moneylog.core.importing.exception.ImportingExceptions.InvalidImportLineTransitionException;
import com.moneylog.core.sk.money.Money;

@DisplayName("Classificação e revisão de linhas importadas")
class ImportLineReviewTest {

    private static final DedupKey CHAVE = DedupKey.of(umaLinhaDeCafe(), 1);

    @Nested
    @DisplayName("classificação")
    class Classificacao {

        @Test
        @DisplayName("chave já existente em movimentação ativa → DUPLICATE")
        void chaveExistenteEDuplicata() {
            ImportLineReview revisao = ImportLineReview.classify(umaLinhaDeCafe(), CHAVE, List.of(umaMovimentacaoImportada(CHAVE)));

            assertThat(revisao.status()).isEqualTo(ImportLineStatus.DUPLICATE);
            assertThat(revisao.matches()).containsExactly(TRANSACTION_ID);
        }

        @Test
        @DisplayName("chave existente só em movimentação estornada → não é duplicata")
        void estornadaNaoConta() {
            ExistingEntry estornada = new ExistingEntry(TRANSACTION_ID, NUBANK_ID, DATE, Money.of("8.50"), ImportDirection.OUT, CHAVE, CHAVE, false, false);

            assertThat(ImportLineReview.classify(umaLinhaDeCafe(), CHAVE, List.of(estornada)).status()).isEqualTo(ImportLineStatus.PENDING);
        }

        @ParameterizedTest(name = "manual em {0} → POSSIBLE_DUPLICATE")
        @CsvSource({"2026-10-09", "2026-10-10", "2026-10-11"})
        @DisplayName("manual com mesma conta, mesmo valor e data ±1 dia → POSSIBLE_DUPLICATE (S-25)")
        void manualParecidaEPossivelDuplicata(LocalDate data) {
            ImportLineReview revisao = ImportLineReview.classify(umaLinhaDeCafe(), CHAVE,
                    List.of(umaMovimentacaoManual(data, "8.50", ImportDirection.OUT)));

            assertThat(revisao.status()).isEqualTo(ImportLineStatus.POSSIBLE_DUPLICATE);
            assertThat(revisao.matches()).containsExactly(TRANSACTION_ID);
        }

        static Stream<Arguments> naoSaoPossivelDuplicata() {
            return Stream.of(
                    Arguments.of("data a 2 dias", umaMovimentacaoManual(DATE.minusDays(2), "8.50", ImportDirection.OUT)),
                    Arguments.of("valor diferente", umaMovimentacaoManual(DATE, "8.51", ImportDirection.OUT)),
                    Arguments.of("direção oposta", umaMovimentacaoManual(DATE, "8.50", ImportDirection.IN)),
                    Arguments.of("outra conta", new ExistingEntry(TRANSACTION_ID, XP_ID, DATE, Money.of("8.50"), ImportDirection.OUT, null, null, true, true)),
                    Arguments.of("não é manual", new ExistingEntry(TRANSACTION_ID, NUBANK_ID, DATE, Money.of("8.50"), ImportDirection.OUT, null, null, false, true)),
                    Arguments.of("manual estornada", new ExistingEntry(TRANSACTION_ID, NUBANK_ID, DATE, Money.of("8.50"), ImportDirection.OUT, null, null, true, false)));
        }

        @ParameterizedTest(name = "{0} → PENDING")
        @MethodSource("naoSaoPossivelDuplicata")
        @DisplayName("fora dos critérios → PENDING")
        void foraDosCriteriosEPendente(String caso, ExistingEntry existente) {
            assertThat(ImportLineReview.classify(umaLinhaDeCafe(), CHAVE, List.of(existente)).status()).isEqualTo(ImportLineStatus.PENDING);
        }

        @Test
        @DisplayName("sem movimentações existentes → PENDING; DUPLICATE tem prioridade sobre POSSIBLE_DUPLICATE")
        void prioridade() {
            assertThat(ImportLineReview.classify(umaLinhaDeCafe(), CHAVE, List.of()).status()).isEqualTo(ImportLineStatus.PENDING);
            assertThat(ImportLineReview.classify(umaLinhaDeCafe(), CHAVE, List.of(
                    umaMovimentacaoManual(DATE, "8.50", ImportDirection.OUT), umaMovimentacaoImportada(CHAVE))).status())
                    .isEqualTo(ImportLineStatus.DUPLICATE);
        }
    }

    @Nested
    @DisplayName("decisão do usuário")
    class Decisao {

        @Test
        @DisplayName("forçar uma DUPLICATE aceita a linha com chave derivada do lote (não viola a unicidade da original)")
        void forcarDuplicata() {
            ImportLineReview revisao = ImportLineReview.classify(umaLinhaDeCafe(), CHAVE, List.of(umaMovimentacaoImportada(CHAVE)));

            revisao.forceImport(BATCH_ID);

            assertThat(revisao.status()).isEqualTo(ImportLineStatus.ACCEPTED);
            assertThat(revisao.isForced()).isTrue();
            assertThat(revisao.key()).isEqualTo(CHAVE);
            assertThat(revisao.effectiveKey()).isEqualTo(CHAVE.forcedIn(BATCH_ID)).isNotEqualTo(CHAVE);
        }

        @Test
        @DisplayName("aceitar uma linha nova grava a chave original")
        void aceitarNova() {
            ImportLineReview revisao = ImportLineReview.classify(umaLinhaDeCafe(), CHAVE, List.of());

            revisao.accept();

            assertThat(revisao.status()).isEqualTo(ImportLineStatus.ACCEPTED);
            assertThat(revisao.isForced()).isFalse();
            assertThat(revisao.effectiveKey()).isEqualTo(CHAVE);
            assertThat(revisao.line()).isEqualTo(umaLinhaDeCafe());
        }

        /** Monta uma revisão no status pedido, pelos caminhos válidos. */
        static ImportLineReview revisaoNoStatus(ImportLineStatus status) {
            return switch (status) {
                case PENDING -> ImportLineReview.classify(umaLinhaDeCafe(), CHAVE, List.of());
                case DUPLICATE -> ImportLineReview.classify(umaLinhaDeCafe(), CHAVE, List.of(umaMovimentacaoImportada(CHAVE)));
                case POSSIBLE_DUPLICATE -> ImportLineReview.classify(umaLinhaDeCafe(), CHAVE, List.of(umaMovimentacaoManual(DATE, "8.50", ImportDirection.OUT)));
                case ACCEPTED -> {
                    ImportLineReview r = revisaoNoStatus(ImportLineStatus.PENDING);
                    r.accept();
                    yield r;
                }
                case REJECTED -> {
                    ImportLineReview r = revisaoNoStatus(ImportLineStatus.PENDING);
                    r.reject();
                    yield r;
                }
            };
        }

        static Stream<Arguments> matriz() {
            Consumer<ImportLineReview> accept = ImportLineReview::accept;
            Consumer<ImportLineReview> reject = ImportLineReview::reject;
            Consumer<ImportLineReview> force = r -> r.forceImport(BATCH_ID);
            return Stream.of(
                    Arguments.of(ImportLineStatus.PENDING, "accept", accept, ImportLineStatus.ACCEPTED),
                    Arguments.of(ImportLineStatus.PENDING, "reject", reject, ImportLineStatus.REJECTED),
                    Arguments.of(ImportLineStatus.PENDING, "forceImport", force, null),
                    Arguments.of(ImportLineStatus.POSSIBLE_DUPLICATE, "accept", accept, ImportLineStatus.ACCEPTED),
                    Arguments.of(ImportLineStatus.POSSIBLE_DUPLICATE, "reject", reject, ImportLineStatus.REJECTED),
                    Arguments.of(ImportLineStatus.POSSIBLE_DUPLICATE, "forceImport", force, null),
                    Arguments.of(ImportLineStatus.DUPLICATE, "accept", accept, null),
                    Arguments.of(ImportLineStatus.DUPLICATE, "reject", reject, ImportLineStatus.REJECTED),
                    Arguments.of(ImportLineStatus.DUPLICATE, "forceImport", force, ImportLineStatus.ACCEPTED),
                    Arguments.of(ImportLineStatus.ACCEPTED, "accept", accept, null),
                    Arguments.of(ImportLineStatus.ACCEPTED, "reject", reject, ImportLineStatus.REJECTED),
                    Arguments.of(ImportLineStatus.ACCEPTED, "forceImport", force, null),
                    Arguments.of(ImportLineStatus.REJECTED, "accept", accept, null),
                    Arguments.of(ImportLineStatus.REJECTED, "reject", reject, null),
                    Arguments.of(ImportLineStatus.REJECTED, "forceImport", force, null));
        }

        @ParameterizedTest(name = "{0} --{1}--> {3}")
        @MethodSource("matriz")
        @DisplayName("matriz completa status × ação (null = transição inválida)")
        void deveRespeitarAMatriz(ImportLineStatus origem, String acao, Consumer<ImportLineReview> transicao, ImportLineStatus destino) {
            ImportLineReview revisao = revisaoNoStatus(origem);
            assertThat(revisao.status()).isEqualTo(origem);

            if (destino == null) {
                assertThatThrownBy(() -> transicao.accept(revisao))
                        .isInstanceOf(InvalidImportLineTransitionException.class)
                        .extracting("code").isEqualTo("IMPORT_LINE_INVALID_TRANSITION");
                assertThat(revisao.status()).isEqualTo(origem);
                assertThat(revisao.effectiveKey()).isEqualTo(CHAVE);
            } else {
                transicao.accept(revisao);
                assertThat(revisao.status()).isEqualTo(destino);
            }
        }

        @Test
        @DisplayName("a matriz cobre todos os status")
        void cobreTodosOsStatus() {
            assertThat(matriz().map(a -> a.get()[0]).distinct()).containsExactlyInAnyOrder((Object[]) ImportLineStatus.values());
        }
    }
}
