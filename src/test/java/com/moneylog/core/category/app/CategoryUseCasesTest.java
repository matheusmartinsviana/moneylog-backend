package com.moneylog.core.category.app;

import static com.moneylog.core.util.Ids.USER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.moneylog.core.category.app.SystemCategories.Definition;
import com.moneylog.core.category.app.gateways.CategoryGateway;
import com.moneylog.core.category.app.usecase.ArchiveCategoryUseCase.ArchiveCategoryCommand;
import com.moneylog.core.category.app.usecase.ArchiveCategoryUseCaseImpl;
import com.moneylog.core.category.app.usecase.CreateCategoryUseCase.CreateCategoryCommand;
import com.moneylog.core.category.app.usecase.CreateCategoryUseCaseImpl;
import com.moneylog.core.category.app.usecase.ListCategoriesUseCase.ListCategoriesQuery;
import com.moneylog.core.category.app.usecase.ListCategoriesUseCaseImpl;
import com.moneylog.core.category.app.usecase.RenameCategoryUseCase.RenameCategoryCommand;
import com.moneylog.core.category.app.usecase.RenameCategoryUseCaseImpl;
import com.moneylog.core.category.app.usecase.SeedSystemCategoriesUseCase.SeedSystemCategoriesCommand;
import com.moneylog.core.category.app.usecase.SeedSystemCategoriesUseCaseImpl;
import com.moneylog.core.category.app.usecase.UnarchiveCategoryUseCase.UnarchiveCategoryCommand;
import com.moneylog.core.category.app.usecase.UnarchiveCategoryUseCaseImpl;
import com.moneylog.core.category.domain.Category;
import com.moneylog.core.category.domain.CategoryKind;
import com.moneylog.core.category.exception.CategoryExceptions.CategoryAlreadyArchivedException;
import com.moneylog.core.category.exception.CategoryExceptions.CategoryNameTakenException;
import com.moneylog.core.category.exception.CategoryExceptions.CategoryNotFoundException;
import com.moneylog.core.category.exception.CategoryExceptions.CategoryNotArchivedException;
import com.moneylog.core.sk.exception.VersionConflictException;
import com.moneylog.core.sk.id.IdGenerator;

@ExtendWith(MockitoExtension.class)
@DisplayName("Casos de uso de categoria (T23)")
class CategoryUseCasesTest {

    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-00000000c0a1");
    private static final UUID OTHER_ID = UUID.fromString("00000000-0000-0000-0000-00000000c0a2");

    @Mock
    private CategoryGateway categories;
    @Mock
    private IdGenerator ids;

    private Category pets(boolean archived, long version) {
        return Category.restore(ID, USER_ID, "Pets", CategoryKind.EXPENSE, false, archived, version);
    }

    @Nested
    @DisplayName("criar")
    class Criar {

        @Test
        @DisplayName("grava a categoria do usuário com o nome sem espaços nas pontas")
        void deveCriar() {
            when(ids.next()).thenReturn(ID);
            when(categories.existsActiveWithName(USER_ID, CategoryKind.EXPENSE, "Pets", null)).thenReturn(false);
            when(categories.save(any())).thenAnswer(call -> call.getArgument(0));

            Category created = new CreateCategoryUseCaseImpl(categories, ids)
                    .handle(new CreateCategoryCommand(USER_ID, "  Pets ", CategoryKind.EXPENSE));

            assertThat(created.name()).isEqualTo("Pets");
            ArgumentCaptor<Category> saved = ArgumentCaptor.forClass(Category.class);
            verify(categories).save(saved.capture());
            assertThat(saved.getValue().isSystem()).isFalse();
            assertThat(saved.getValue().userId()).isEqualTo(USER_ID);
        }

        @Test
        @DisplayName("nome já usado por uma ativa do mesmo tipo é 409 CATEGORY_NAME_TAKEN e nada é gravado")
        void deveRecusarNomeRepetido() {
            when(ids.next()).thenReturn(ID);
            when(categories.existsActiveWithName(USER_ID, CategoryKind.EXPENSE, "pets", null)).thenReturn(true);

            assertThatThrownBy(() -> new CreateCategoryUseCaseImpl(categories, ids)
                    .handle(new CreateCategoryCommand(USER_ID, "pets", CategoryKind.EXPENSE)))
                    .isInstanceOf(CategoryNameTakenException.class).extracting("code").isEqualTo("CATEGORY_NAME_TAKEN");

            verify(categories, never()).save(any());
        }
    }

    @Nested
    @DisplayName("renomear")
    class Renomear {

        @Test
        @DisplayName("troca o nome, conferindo a unicidade sem contar a própria")
        void deveRenomear() {
            when(categories.findById(USER_ID, ID)).thenReturn(Optional.of(pets(false, 3)));
            when(categories.existsActiveWithName(USER_ID, CategoryKind.EXPENSE, "Bichos", ID)).thenReturn(false);
            when(categories.save(any())).thenAnswer(call -> call.getArgument(0));

            Category renamed = new RenameCategoryUseCaseImpl(categories).handle(new RenameCategoryCommand(USER_ID, ID, "Bichos", 3));

            assertThat(renamed.name()).isEqualTo("Bichos");
        }

        @Test
        @DisplayName("versão desatualizada é 409 VERSION_CONFLICT e nada é gravado")
        void deveRecusarVersaoAntiga() {
            when(categories.findById(USER_ID, ID)).thenReturn(Optional.of(pets(false, 3)));

            assertThatThrownBy(() -> new RenameCategoryUseCaseImpl(categories).handle(new RenameCategoryCommand(USER_ID, ID, "X", 2)))
                    .isInstanceOf(VersionConflictException.class).extracting("code").isEqualTo("VERSION_CONFLICT");

            verify(categories, never()).save(any());
        }

        @Test
        @DisplayName("inexistente ou de outro usuário é CATEGORY_NOT_FOUND")
        void deveRecusarInexistente() {
            when(categories.findById(USER_ID, ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> new RenameCategoryUseCaseImpl(categories).handle(new RenameCategoryCommand(USER_ID, ID, "X", 0)))
                    .isInstanceOf(CategoryNotFoundException.class);
        }

        @Test
        @DisplayName("nome de outra ativa é 409; categoria arquivada pode ser renomeada sem conferir (o desarquivar confere)")
        void deveConferirNomeSoDasAtivas() {
            when(categories.findById(USER_ID, ID)).thenReturn(Optional.of(pets(false, 0)));
            when(categories.existsActiveWithName(USER_ID, CategoryKind.EXPENSE, "Bichos", ID)).thenReturn(true);
            assertThatThrownBy(() -> new RenameCategoryUseCaseImpl(categories).handle(new RenameCategoryCommand(USER_ID, ID, "Bichos", 0)))
                    .isInstanceOf(CategoryNameTakenException.class);

            when(categories.findById(USER_ID, OTHER_ID)).thenReturn(Optional.of(
                    Category.restore(OTHER_ID, USER_ID, "Velho", CategoryKind.EXPENSE, false, true, 0)));
            when(categories.save(any())).thenAnswer(call -> call.getArgument(0));
            Category renamed = new RenameCategoryUseCaseImpl(categories).handle(new RenameCategoryCommand(USER_ID, OTHER_ID, "Bichos", 0));

            assertThat(renamed.name()).isEqualTo("Bichos");
            verify(categories, never()).existsActiveWithName(USER_ID, CategoryKind.EXPENSE, "Bichos", OTHER_ID);
        }
    }

    @Nested
    @DisplayName("arquivar e desarquivar")
    class Arquivar {

        @Test
        @DisplayName("arquiva e grava; arquivar de novo é 422")
        void deveArquivar() {
            when(categories.findById(USER_ID, ID)).thenReturn(Optional.of(pets(false, 1)), Optional.of(pets(true, 2)));
            when(categories.save(any())).thenAnswer(call -> call.getArgument(0));
            ArchiveCategoryUseCaseImpl useCase = new ArchiveCategoryUseCaseImpl(categories);

            assertThat(useCase.handle(new ArchiveCategoryCommand(USER_ID, ID)).isArchived()).isTrue();
            assertThatThrownBy(() -> useCase.handle(new ArchiveCategoryCommand(USER_ID, ID)))
                    .isInstanceOf(CategoryAlreadyArchivedException.class);
            verify(categories, times(1)).save(any());
        }

        @Test
        @DisplayName("arquivar inexistente é 404")
        void deveRecusarArquivarInexistente() {
            when(categories.findById(USER_ID, ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> new ArchiveCategoryUseCaseImpl(categories).handle(new ArchiveCategoryCommand(USER_ID, ID)))
                    .isInstanceOf(CategoryNotFoundException.class);
        }

        @Test
        @DisplayName("desarquivar com o nome já usado por uma ativa é 409 e a categoria não é gravada")
        void deveRecusarDesarquivarComNomeRepetido() {
            when(categories.findById(USER_ID, ID)).thenReturn(Optional.of(pets(true, 2)));
            when(categories.existsActiveWithName(USER_ID, CategoryKind.EXPENSE, "Pets", ID)).thenReturn(true);

            assertThatThrownBy(() -> new UnarchiveCategoryUseCaseImpl(categories).handle(new UnarchiveCategoryCommand(USER_ID, ID)))
                    .isInstanceOf(CategoryNameTakenException.class);

            verify(categories, never()).save(any());
        }

        @Test
        @DisplayName("desarquiva quando o nome está livre; desarquivar uma ativa é 422; inexistente é 404")
        void deveDesarquivar() {
            when(categories.findById(USER_ID, ID)).thenReturn(Optional.of(pets(true, 2)), Optional.of(pets(false, 3)));
            when(categories.existsActiveWithName(USER_ID, CategoryKind.EXPENSE, "Pets", ID)).thenReturn(false);
            when(categories.save(any())).thenAnswer(call -> call.getArgument(0));
            UnarchiveCategoryUseCaseImpl useCase = new UnarchiveCategoryUseCaseImpl(categories);

            assertThat(useCase.handle(new UnarchiveCategoryCommand(USER_ID, ID)).isArchived()).isFalse();
            assertThatThrownBy(() -> useCase.handle(new UnarchiveCategoryCommand(USER_ID, ID)))
                    .isInstanceOf(CategoryNotArchivedException.class);
            when(categories.findById(USER_ID, OTHER_ID)).thenReturn(Optional.empty());
            assertThatThrownBy(() -> useCase.handle(new UnarchiveCategoryCommand(USER_ID, OTHER_ID)))
                    .isInstanceOf(CategoryNotFoundException.class);
        }
    }

    @Test
    @DisplayName("listar delega ao gateway com o filtro de tipo e de arquivadas")
    void deveListar() {
        when(categories.list(USER_ID, CategoryKind.EXPENSE, true)).thenReturn(List.of(pets(true, 1)));

        assertThat(new ListCategoriesUseCaseImpl(categories).handle(new ListCategoriesQuery(USER_ID, CategoryKind.EXPENSE, true)))
                .hasSize(1);
    }

    @Nested
    @DisplayName("seed das categorias de sistema (S-59)")
    class Seed {

        @Test
        @DisplayName("cria 13 de despesa e 3 de receita; nenhuma é de investimento (S-38)")
        void deveCriarAsDezesseis() {
            when(categories.systemKeysOf(USER_ID)).thenReturn(Set.of());
            when(ids.next()).thenAnswer(call -> UUID.randomUUID());

            int created = new SeedSystemCategoriesUseCaseImpl(categories, ids).handle(new SeedSystemCategoriesCommand(USER_ID));

            assertThat(created).isEqualTo(16);
            ArgumentCaptor<Category> saved = ArgumentCaptor.forClass(Category.class);
            ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
            verify(categories, times(16)).saveSystem(saved.capture(), keys.capture());
            assertThat(saved.getAllValues()).allMatch(Category::isSystem);
            assertThat(saved.getAllValues().stream().filter(c -> c.kind() == CategoryKind.EXPENSE)).hasSize(13);
            assertThat(saved.getAllValues().stream().filter(c -> c.kind() == CategoryKind.INCOME).map(Category::name))
                    .containsExactlyInAnyOrder("Salário", "Trabalho extra", "Outras receitas");
            assertThat(saved.getAllValues().stream().map(Category::name).map(String::toLowerCase))
                    .noneMatch(name -> name.contains("investiment"));
            assertThat(keys.getAllValues()).doesNotHaveDuplicates().noneMatch(key -> key.contains("INVEST"));
        }

        @Test
        @DisplayName("é idempotente: só cria as chaves que faltam, mesmo que tenham sido renomeadas ou arquivadas")
        void deveSerIdempotente() {
            Set<String> all = SystemCategories.ALL.stream().map(Definition::key).collect(Collectors.toSet());
            when(categories.systemKeysOf(USER_ID)).thenReturn(all);

            assertThat(new SeedSystemCategoriesUseCaseImpl(categories, ids).handle(new SeedSystemCategoriesCommand(USER_ID))).isZero();
            verify(categories, never()).saveSystem(any(), anyString());

            Set<String> missingOne = SystemCategories.ALL.stream().map(Definition::key).filter(k -> !k.equals("SALARY")).collect(Collectors.toSet());
            when(categories.systemKeysOf(USER_ID)).thenReturn(missingOne);
            when(ids.next()).thenReturn(ID);
            assertThat(new SeedSystemCategoriesUseCaseImpl(categories, ids).handle(new SeedSystemCategoriesCommand(USER_ID))).isEqualTo(1);
            verify(categories).saveSystem(any(Category.class), eq("SALARY"));
        }
    }
}
