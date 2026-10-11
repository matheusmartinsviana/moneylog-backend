package com.moneylog.infra.category.controller;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

import com.moneylog.core.category.app.usecase.ArchiveCategoryUseCase;
import com.moneylog.core.category.app.usecase.ArchiveCategoryUseCase.ArchiveCategoryCommand;
import com.moneylog.core.category.app.usecase.CreateCategoryUseCase;
import com.moneylog.core.category.app.usecase.CreateCategoryUseCase.CreateCategoryCommand;
import com.moneylog.core.category.app.usecase.ListCategoriesUseCase;
import com.moneylog.core.category.app.usecase.ListCategoriesUseCase.ListCategoriesQuery;
import com.moneylog.core.category.app.usecase.RenameCategoryUseCase;
import com.moneylog.core.category.app.usecase.RenameCategoryUseCase.RenameCategoryCommand;
import com.moneylog.core.category.app.usecase.UnarchiveCategoryUseCase;
import com.moneylog.core.category.app.usecase.UnarchiveCategoryUseCase.UnarchiveCategoryCommand;
import com.moneylog.core.category.domain.Category;
import com.moneylog.core.category.domain.CategoryKind;
import com.moneylog.infra.category.controller.CategoryRequests.CategoryInput;
import com.moneylog.infra.category.controller.CategoryRequests.CategoryUpdate;
import com.moneylog.infra.security.CurrentUser;

@RestController
@RequestMapping("/v1/categories")
public class CategoryController {

    private final ListCategoriesUseCase list;
    private final CreateCategoryUseCase create;
    private final RenameCategoryUseCase rename;
    private final ArchiveCategoryUseCase archive;
    private final UnarchiveCategoryUseCase unarchive;

    public CategoryController(ListCategoriesUseCase list, CreateCategoryUseCase create, RenameCategoryUseCase rename,
                              ArchiveCategoryUseCase archive, UnarchiveCategoryUseCase unarchive) {
        this.list = list;
        this.create = create;
        this.rename = rename;
        this.archive = archive;
        this.unarchive = unarchive;
    }

    @GetMapping
    public List<CategoryResponse> list(@CurrentUser UUID userId, @RequestParam(required = false) CategoryKind kind,
                                       @RequestParam(defaultValue = "false") boolean includeArchived) {
        return list.handle(new ListCategoriesQuery(userId, kind, includeArchived)).stream().map(CategoryResponse::of).toList();
    }

    @PostMapping
    public ResponseEntity<CategoryResponse> create(@CurrentUser UUID userId, @Valid @RequestBody CategoryInput body) {
        Category category = create.handle(new CreateCategoryCommand(userId, body.name(), body.kind()));
        return ResponseEntity.created(URI.create("/v1/categories/" + category.id())).body(CategoryResponse.of(category));
    }

    @PutMapping("/{id}")
    public CategoryResponse rename(@CurrentUser UUID userId, @PathVariable UUID id, @Valid @RequestBody CategoryUpdate body) {
        return CategoryResponse.of(rename.handle(new RenameCategoryCommand(userId, id, body.name(), body.version())));
    }

    @PostMapping("/{id}/archive")
    public CategoryResponse archive(@CurrentUser UUID userId, @PathVariable UUID id) {
        return CategoryResponse.of(archive.handle(new ArchiveCategoryCommand(userId, id)));
    }

    @PostMapping("/{id}/unarchive")
    public CategoryResponse unarchive(@CurrentUser UUID userId, @PathVariable UUID id) {
        return CategoryResponse.of(unarchive.handle(new UnarchiveCategoryCommand(userId, id)));
    }
}
