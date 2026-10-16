package com.moneylog.infra.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import com.moneylog.core.goal.photo.PhotoImagePipeline;
import com.moneylog.core.goal.photo.PhotoLimits;
import com.moneylog.core.profile.app.gateways.ProfilePhotoGateway;
import com.moneylog.core.profile.app.usecase.ProfilePhotoUseCases.GetProfilePhotoContentUseCase;
import com.moneylog.core.profile.app.usecase.ProfilePhotoUseCases.GetProfilePhotoInfoUseCase;
import com.moneylog.core.profile.app.usecase.ProfilePhotoUseCases.RemoveProfilePhotoUseCase;
import com.moneylog.core.profile.app.usecase.ProfilePhotoUseCases.ReplaceProfilePhotoUseCase;
import com.moneylog.core.profile.app.usecase.ProfilePhotoUseCasesImpl;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.infra.profile.gateways.ProfilePhotoGatewayImpl;

@Configuration(proxyBeanMethods = false)
public class ProfileConfig {

    @Bean
    ProfilePhotoGateway profilePhotoGateway(JdbcTemplate jdbc) {
        return new ProfilePhotoGatewayImpl(jdbc);
    }

    @Bean
    ReplaceProfilePhotoUseCase replaceProfilePhotoUseCase(ProfilePhotoGateway gateway, PhotoImagePipeline pipeline, PhotoLimits limits, TimeProvider time,
                                                          PlatformTransactionManager tm) {
        return TransactionalProxies.required(ReplaceProfilePhotoUseCase.class, new ProfilePhotoUseCasesImpl(gateway, pipeline, limits, time), tm);
    }

    @Bean
    GetProfilePhotoContentUseCase getProfilePhotoContentUseCase(ProfilePhotoGateway gateway, PhotoImagePipeline pipeline, PhotoLimits limits, TimeProvider time,
                                                                PlatformTransactionManager tm) {
        return TransactionalProxies.readOnly(GetProfilePhotoContentUseCase.class, new ProfilePhotoUseCasesImpl(gateway, pipeline, limits, time), tm);
    }

    @Bean
    RemoveProfilePhotoUseCase removeProfilePhotoUseCase(ProfilePhotoGateway gateway, PhotoImagePipeline pipeline, PhotoLimits limits, TimeProvider time,
                                                        PlatformTransactionManager tm) {
        return TransactionalProxies.required(RemoveProfilePhotoUseCase.class, new ProfilePhotoUseCasesImpl(gateway, pipeline, limits, time), tm);
    }

    @Bean
    GetProfilePhotoInfoUseCase getProfilePhotoInfoUseCase(ProfilePhotoGateway gateway, PhotoImagePipeline pipeline, PhotoLimits limits, TimeProvider time,
                                                          PlatformTransactionManager tm) {
        return TransactionalProxies.readOnly(GetProfilePhotoInfoUseCase.class, new ProfilePhotoUseCasesImpl(gateway, pipeline, limits, time), tm);
    }
}
