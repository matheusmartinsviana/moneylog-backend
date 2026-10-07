package com.moneylog.infra.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Parâmetro {@code UUID} de controller que recebe o id do usuário do token (claim {@code sub}). Todo endpoint filtra
 * pelo usuário do token; nunca confia em id vindo do corpo ou da URL.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CurrentUser {
}
