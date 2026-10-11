package com.moneylog.core.category.exception;

import java.util.UUID;

import com.moneylog.core.category.domain.CategoryKind;
import com.moneylog.core.sk.exception.BusinessRuleException;
import com.moneylog.core.sk.exception.ConflictException;
import com.moneylog.core.sk.exception.NotFoundException;

public final class CategoryExceptions {

    private CategoryExceptions() {
    }

    public static class CategoryNotFoundException extends NotFoundException {
        public CategoryNotFoundException(UUID categoryId) {
            super("CATEGORY_NOT_FOUND", "Categoria não encontrada: " + categoryId + ".");
        }
    }

    public static class DefaultCategoryUnavailableException extends BusinessRuleException {
        public DefaultCategoryUnavailableException(String systemKey) {
            super("DEFAULT_CATEGORY_UNAVAILABLE", "A categoria padrão (" + systemKey
                    + ") não existe ou está arquivada; informe 'categoryId' ou desarquive a categoria.");
        }
    }

    public static class CategoryOwnerRequiredException extends BusinessRuleException {
        public CategoryOwnerRequiredException() {
            super("CATEGORY_OWNER_REQUIRED", "A categoria precisa pertencer a um usuário.");
        }
    }

    public static class CategoryNameRequiredException extends BusinessRuleException {
        public CategoryNameRequiredException() {
            super("CATEGORY_NAME_REQUIRED", "O nome da categoria é obrigatório.");
        }
    }

    public static class CategoryNameTooLongException extends BusinessRuleException {
        public CategoryNameTooLongException(int max) {
            super("CATEGORY_NAME_TOO_LONG", "O nome da categoria pode ter no máximo " + max + " caracteres.");
        }
    }

    public static class CategoryFieldRequiredException extends BusinessRuleException {
        public CategoryFieldRequiredException(String field) {
            super("CATEGORY_FIELD_REQUIRED", "Campo obrigatório da categoria ausente: " + field + ".");
        }
    }

    public static class CategoryKindMismatchException extends BusinessRuleException {
        public CategoryKindMismatchException(CategoryKind expected, CategoryKind actual) {
            super("CATEGORY_KIND_MISMATCH", "A categoria é de " + actual + ", mas a movimentação exige " + expected + ".");
        }
    }

    public static class CategoryArchivedException extends BusinessRuleException {
        public CategoryArchivedException() {
            super("CATEGORY_ARCHIVED", "A categoria está arquivada e não recebe movimentações novas.");
        }
    }

    public static class CategoryAlreadyArchivedException extends BusinessRuleException {
        public CategoryAlreadyArchivedException() {
            super("CATEGORY_ALREADY_ARCHIVED", "A categoria já está arquivada.");
        }
    }

    public static class CategoryNotArchivedException extends BusinessRuleException {
        public CategoryNotArchivedException() {
            super("CATEGORY_NOT_ARCHIVED", "A categoria não está arquivada.");
        }
    }

    public static class CategoryNameTakenException extends ConflictException {
        public CategoryNameTakenException(String name) {
            super("CATEGORY_NAME_TAKEN", "Já existe uma categoria ativa chamada \"" + name + "\" nesse tipo.");
        }
    }
}
