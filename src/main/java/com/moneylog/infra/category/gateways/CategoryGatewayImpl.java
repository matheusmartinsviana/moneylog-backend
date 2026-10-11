package com.moneylog.infra.category.gateways;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;

import com.moneylog.core.category.app.gateways.CategoryGateway;
import com.moneylog.core.category.domain.Category;
import com.moneylog.core.category.domain.CategoryKind;
import com.moneylog.core.category.exception.CategoryExceptions.CategoryNameTakenException;
import com.moneylog.core.sk.exception.VersionConflictException;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.infra.category.persistence.CategoryEntity;
import com.moneylog.infra.category.persistence.CategoryRepositoryJpa;

public class CategoryGatewayImpl implements CategoryGateway {

    private static final UUID NO_ID = new UUID(0L, 0L);

    private final CategoryRepositoryJpa repository;
    private final TimeProvider time;

    public CategoryGatewayImpl(CategoryRepositoryJpa repository, TimeProvider time) {
        this.repository = repository;
        this.time = time;
    }

    @Override
    public Category save(Category category) {
        CategoryEntity entity = repository.findByIdAndUserId(category.id(), category.userId()).orElse(null);
        if (entity == null) {
            return insert(new CategoryEntity(category.id(), category.userId(), category.name(), category.kind(),
                    category.isSystem(), null, category.isArchived(), time.now()), category.name());
        }
        if (entity.getVersion() != category.version()) {
            throw new VersionConflictException(category.version(), entity.getVersion());
        }
        entity.apply(category.name(), category.isArchived(), time.now());
        return flush(entity, category.name());
    }

    @Override
    public Category saveSystem(Category category, String systemKey) {
        return insert(new CategoryEntity(category.id(), category.userId(), category.name(), category.kind(), true, systemKey,
                category.isArchived(), time.now()), category.name());
    }

    @Override
    public Optional<Category> findById(UUID userId, UUID id) {
        return repository.findByIdAndUserId(id, userId).map(CategoryRepositoryMapper::toDomain);
    }

    @Override
    public List<Category> list(UUID userId, CategoryKind kind, boolean includeArchived) {
        List<CategoryEntity> entities = kind == null ? repository.findAllOrdered(userId, includeArchived)
                : repository.findByKindOrdered(userId, kind, includeArchived);
        return entities.stream().map(CategoryRepositoryMapper::toDomain).toList();
    }

    @Override
    public boolean existsActiveWithName(UUID userId, CategoryKind kind, String name, UUID excludingId) {
        return repository.existsActiveWithName(userId, kind, name, excludingId == null ? NO_ID : excludingId);
    }

    @Override
    public Optional<Category> findBySystemKey(UUID userId, String systemKey) {
        return repository.findByUserIdAndSystemKey(userId, systemKey).map(CategoryRepositoryMapper::toDomain);
    }

    @Override
    public Set<String> systemKeysOf(UUID userId) {
        return repository.findSystemKeys(userId);
    }

    private Category insert(CategoryEntity entity, String name) {
        return flush(repository.save(entity), name);
    }

    private Category flush(CategoryEntity entity, String name) {
        try {
            repository.saveAndFlush(entity);
        } catch (DataIntegrityViolationException e) {
            if (String.valueOf(e.getMostSpecificCause().getMessage()).contains("uq_categories_user_kind_name_active")) {
                throw new CategoryNameTakenException(name);
            }
            throw e;
        }
        return CategoryRepositoryMapper.toDomain(entity);
    }
}
