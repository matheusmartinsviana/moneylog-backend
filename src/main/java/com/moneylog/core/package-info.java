/**
 * Regras de negócio do moneylog, organizadas por feature.
 *
 * <p>O core é Java puro: não depende de Spring, JPA, Jackson, Lombok nem de {@code com.moneylog.infra}.
 * A regra é verificada pelo {@code ArchitectureTest}. Dinheiro é sempre {@code Money} (centavos {@code long}),
 * nunca {@code double}/{@code float}.
 */
package com.moneylog.core;
