package com.moneylog.infra.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import com.moneylog.core.category.app.gateways.CategoryGateway;
import com.moneylog.core.category.app.usecase.ArchiveCategoryUseCase;
import com.moneylog.core.category.app.usecase.ArchiveCategoryUseCaseImpl;
import com.moneylog.core.category.app.usecase.CreateCategoryUseCase;
import com.moneylog.core.category.app.usecase.CreateCategoryUseCaseImpl;
import com.moneylog.core.category.app.usecase.ListCategoriesUseCase;
import com.moneylog.core.category.app.usecase.ListCategoriesUseCaseImpl;
import com.moneylog.core.category.app.usecase.RenameCategoryUseCase;
import com.moneylog.core.category.app.usecase.RenameCategoryUseCaseImpl;
import com.moneylog.core.category.app.usecase.SeedSystemCategoriesUseCase;
import com.moneylog.core.category.app.usecase.SeedSystemCategoriesUseCaseImpl;
import com.moneylog.core.category.app.usecase.UnarchiveCategoryUseCase;
import com.moneylog.core.category.app.usecase.UnarchiveCategoryUseCaseImpl;
import com.moneylog.core.sk.id.IdGenerator;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.core.auth.app.gateways.UserGateway;
import com.moneylog.infra.category.CategorySeedListener;
import com.moneylog.infra.category.CategorySeedRunner;
import com.moneylog.infra.category.gateways.CategoryGatewayImpl;
import com.moneylog.infra.category.persistence.CategoryRepositoryJpa;

/** Ligação da feature categoria: gateway e casos de uso com a fronteira transacional. */
@Configuration(proxyBeanMethods = false)
public class CategoryConfig {

    @Bean
    CategoryGateway categoryGateway(CategoryRepositoryJpa repository, TimeProvider time) {
        return new CategoryGatewayImpl(repository, time);
    }

    @Bean
    ListCategoriesUseCase listCategoriesUseCase(CategoryGateway categories, PlatformTransactionManager tm) {
        return TransactionalProxies.readOnly(ListCategoriesUseCase.class, new ListCategoriesUseCaseImpl(categories), tm);
    }

    @Bean
    CreateCategoryUseCase createCategoryUseCase(CategoryGateway categories, IdGenerator ids, PlatformTransactionManager tm) {
        return TransactionalProxies.required(CreateCategoryUseCase.class, new CreateCategoryUseCaseImpl(categories, ids), tm);
    }

    @Bean
    RenameCategoryUseCase renameCategoryUseCase(CategoryGateway categories, PlatformTransactionManager tm) {
        return TransactionalProxies.required(RenameCategoryUseCase.class, new RenameCategoryUseCaseImpl(categories), tm);
    }

    @Bean
    ArchiveCategoryUseCase archiveCategoryUseCase(CategoryGateway categories, PlatformTransactionManager tm) {
        return TransactionalProxies.required(ArchiveCategoryUseCase.class, new ArchiveCategoryUseCaseImpl(categories), tm);
    }

    @Bean
    UnarchiveCategoryUseCase unarchiveCategoryUseCase(CategoryGateway categories, PlatformTransactionManager tm) {
        return TransactionalProxies.required(UnarchiveCategoryUseCase.class, new UnarchiveCategoryUseCaseImpl(categories), tm);
    }

    @Bean
    SeedSystemCategoriesUseCase seedSystemCategoriesUseCase(CategoryGateway categories, IdGenerator ids,
                                                           PlatformTransactionManager tm) {
        return TransactionalProxies.required(SeedSystemCategoriesUseCase.class,
                new SeedSystemCategoriesUseCaseImpl(categories, ids), tm);
    }

    @Bean
    CategorySeedListener categorySeedListener(SeedSystemCategoriesUseCase seed) {
        return new CategorySeedListener(seed);
    }

    @Bean
    CategorySeedRunner categorySeedRunner(UserGateway users, SeedSystemCategoriesUseCase seed) {
        return new CategorySeedRunner(users, seed);
    }
}
