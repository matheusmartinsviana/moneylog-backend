package com.moneylog.core.category.domain;

import static com.moneylog.core.category.util.factory.CategoryTestFactory.ALIMENTACAO_ID;
import static com.moneylog.core.category.util.factory.CategoryTestFactory.SALARIO_ID;
import static com.moneylog.core.category.util.factory.CategoryTestFactory.umaCategoriaArquivada;
import static com.moneylog.core.category.util.factory.CategoryTestFactory.umaCategoriaDeDespesa;
import static com.moneylog.core.category.util.factory.CategoryTestFactory.umaCategoriaDeReceita;
import static com.moneylog.core.util.Ids.OTHER_USER_ID;
import static com.moneylog.core.util.Ids.USER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.moneylog.core.category.exception.CategoryExceptions.CategoryAlreadyArchivedException;
import com.moneylog.core.category.exception.CategoryExceptions.CategoryArchivedException;
import com.moneylog.core.category.exception.CategoryExceptions.CategoryFieldRequiredException;
import com.moneylog.core.category.exception.CategoryExceptions.CategoryKindMismatchException;
import com.moneylog.core.category.exception.CategoryExceptions.CategoryNameRequiredException;
import com.moneylog.core.category.exception.CategoryExceptions.CategoryNameTooLongException;
import com.moneylog.core.category.exception.CategoryExceptions.CategoryNotArchivedException;
import com.moneylog.core.category.exception.CategoryExceptions.CategoryNotFoundException;
import com.moneylog.core.category.exception.CategoryExceptions.CategoryOwnerRequiredException;
import com.moneylog.core.sk.exception.BusinessRuleException;
import com.moneylog.core.sk.ledger.BookRef;

@DisplayName("Category")
class CategoryTest {

    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-00000000c099");

    @Test
    @DisplayName("categoria INCOME usada em despesa lança CategoryKindMismatchException")
    void deveRejeitarNaturezaErrada() {
        assertThatThrownBy(() -> umaCategoriaDeReceita().ensureKind(CategoryKind.EXPENSE))
                .isInstanceOf(CategoryKindMismatchException.class)
                .isInstanceOf(BusinessRuleException.class)
                .extracting("code").isEqualTo("CATEGORY_KIND_MISMATCH");
        assertThatThrownBy(() -> umaCategoriaDeDespesa().ensureKind(CategoryKind.INCOME))
                .isInstanceOf(CategoryKindMismatchException.class);
    }

    @Test
    @DisplayName("categoria da natureza certa passa")
    void deveAceitarNaturezaCerta() {
        assertThatCode(() -> umaCategoriaDeDespesa().ensureKind(CategoryKind.EXPENSE)).doesNotThrowAnyException();
        assertThatCode(() -> umaCategoriaDeReceita().ensureKind(CategoryKind.INCOME)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("o livro da categoria segue a natureza: EXPENSE(cat) ou INCOME(cat)")
    void deveApontarOLivro() {
        assertThat(umaCategoriaDeDespesa().book()).isEqualTo(BookRef.expense(ALIMENTACAO_ID));
        assertThat(umaCategoriaDeReceita().book()).isEqualTo(BookRef.income(SALARIO_ID));
    }

    @Test
    @DisplayName("criada pelo usuário não é de sistema; seed é de sistema")
    void deveDistinguirSistema() {
        Category propria = Category.create(ID, USER_ID, " Pets ", CategoryKind.EXPENSE);

        assertThat(propria.isSystem()).isFalse();
        assertThat(propria.name()).isEqualTo("Pets");
        assertThat(propria.isArchived()).isFalse();
        assertThat(propria.version()).isZero();
        assertThat(propria.userId()).isEqualTo(USER_ID);
        assertThat(propria.kind()).isEqualTo(CategoryKind.EXPENSE);
        assertThat(propria.id()).isEqualTo(ID);
        assertThat(umaCategoriaDeDespesa().isSystem()).isTrue();
        assertThat(umaCategoriaDeDespesa()).isEqualTo(umaCategoriaDeDespesa()).hasSameHashCodeAs(umaCategoriaDeDespesa());
        assertThat(umaCategoriaArquivada().toString()).contains("Alimentação", "ARCHIVED");
    }

    @ParameterizedTest(name = "nome \"{0}\"")
    @NullSource
    @ValueSource(strings = {"", "  "})
    @DisplayName("nome em branco é rejeitado")
    void deveRejeitarNomeEmBranco(String nome) {
        assertThatThrownBy(() -> Category.create(ID, USER_ID, nome, CategoryKind.EXPENSE))
                .isInstanceOf(CategoryNameRequiredException.class);
    }

    @Test
    @DisplayName("nome longo, usuário, id ou natureza ausentes são rejeitados com exceção específica")
    void deveValidarCampos() {
        assertThatThrownBy(() -> Category.create(ID, USER_ID, "x".repeat(61), CategoryKind.EXPENSE))
                .isInstanceOf(CategoryNameTooLongException.class);
        assertThatThrownBy(() -> Category.create(ID, null, "Pets", CategoryKind.EXPENSE))
                .isInstanceOf(CategoryOwnerRequiredException.class);
        assertThatThrownBy(() -> Category.create(null, USER_ID, "Pets", CategoryKind.EXPENSE))
                .isInstanceOf(CategoryFieldRequiredException.class).hasMessageContaining("id");
        assertThatThrownBy(() -> Category.create(ID, USER_ID, "Pets", null))
                .isInstanceOf(CategoryFieldRequiredException.class).hasMessageContaining("kind");
    }

    @ParameterizedTest(name = "arquivada={0} --{1}--> {2}")
    @CsvSource({
            "false, archive,   true,",
            "false, unarchive, ,     CATEGORY_NOT_ARCHIVED",
            "true,  archive,   ,     CATEGORY_ALREADY_ARCHIVED",
            "true,  unarchive, false,"})
    @DisplayName("matriz de transições de arquivamento (de sistema também pode ser arquivada)")
    void deveRespeitarAMatriz(boolean arquivada, String acao, Boolean depois, String erro) {
        Category categoria = Category.restore(ALIMENTACAO_ID, USER_ID, "Alimentação", CategoryKind.EXPENSE, true, arquivada, 1L);
        Runnable transicao = acao.equals("archive") ? categoria::archive : categoria::unarchive;

        if (erro != null) {
            assertThatThrownBy(transicao::run).isInstanceOf(BusinessRuleException.class).extracting("code").isEqualTo(erro);
            assertThat(categoria.isArchived()).isEqualTo(arquivada);
        } else {
            transicao.run();
            assertThat(categoria.isArchived()).isEqualTo(depois);
        }
    }

    @Test
    @DisplayName("as exceções de transição têm tipo próprio")
    void excecoesDeTransicao() {
        assertThatThrownBy(() -> umaCategoriaArquivada().archive()).isInstanceOf(CategoryAlreadyArchivedException.class);
        assertThatThrownBy(() -> umaCategoriaDeDespesa().unarchive()).isInstanceOf(CategoryNotArchivedException.class);
    }

    @Test
    @DisplayName("categoria arquivada não recebe movimentação nova")
    void arquivadaNaoRecebeMovimentacao() {
        assertThatThrownBy(() -> umaCategoriaArquivada().ensureCanReceiveTransactions())
                .isInstanceOf(CategoryArchivedException.class);
        assertThatCode(() -> umaCategoriaDeDespesa().ensureCanReceiveTransactions()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("categoria de outro usuário é tratada como não encontrada")
    void outroUsuarioNaoEncontra() {
        assertThatCode(() -> umaCategoriaDeDespesa().ensureBelongsTo(USER_ID)).doesNotThrowAnyException();
        assertThatThrownBy(() -> umaCategoriaDeDespesa().ensureBelongsTo(OTHER_USER_ID))
                .isInstanceOf(CategoryNotFoundException.class);
    }

    @Test
    @DisplayName("rename reaplica a invariante do nome; não há setters públicos")
    void renameSemSetters() {
        Category categoria = umaCategoriaDeDespesa();

        categoria.rename("Comida");
        assertThat(categoria.name()).isEqualTo("Comida");
        assertThatThrownBy(() -> categoria.rename("")).isInstanceOf(CategoryNameRequiredException.class);
        assertThat(categoria.name()).isEqualTo("Comida");

        assertThat(Arrays.stream(Category.class.getMethods()))
                .filteredOn(m -> Modifier.isPublic(m.getModifiers()))
                .extracting(Method::getName)
                .noneMatch(nome -> nome.startsWith("set"));
        assertThat(Category.class.getConstructors()).isEmpty();
    }
}
