package com.moneylog.infra.web.validation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/** A data está na janela financeira (2000-01-01 a 2100-12-31, S-114); {@code null} passa (use {@code @NotNull}). */
@Documented
@Constraint(validatedBy = DateInWindowValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface DateInWindow {

    String message() default "deve estar entre 2000-01-01 e 2100-12-31";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
