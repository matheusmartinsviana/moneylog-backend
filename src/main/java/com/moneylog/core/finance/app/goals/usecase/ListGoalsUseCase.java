package com.moneylog.core.finance.app.goals.usecase;

import java.util.UUID;

import com.moneylog.core.finance.app.goals.GoalDetails;
import com.moneylog.core.goal.domain.GoalStatus;
import com.moneylog.core.sk.page.PageQuery;
import com.moneylog.core.sk.page.Slice;

public interface ListGoalsUseCase {

    /**
     * Lista as metas. O {@code status} filtra pelo status <b>derivado</b> do domínio ({@code Goal.status}, a mesma função da meta
     * individual); sem ele, devolve todas. Ordem: {@code ACTIVE}, {@code ACHIEVED}, {@code ARCHIVED}; depois prioridade, prazo (sem
     * prazo por último) e nome.
     */
    Slice<GoalDetails> handle(ListGoalsQuery query);

    record ListGoalsQuery(UUID userId, GoalStatus status, PageQuery page) {
    }
}
