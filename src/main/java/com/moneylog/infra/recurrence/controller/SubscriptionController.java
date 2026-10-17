package com.moneylog.infra.recurrence.controller;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.moneylog.core.recurrence.app.SubscriptionStatus;
import com.moneylog.core.recurrence.app.usecase.ListSubscriptionsUseCase;
import com.moneylog.core.recurrence.app.usecase.ListSubscriptionsUseCase.ListSubscriptionsQuery;
import com.moneylog.core.recurrence.domain.RecurrenceScope;
import com.moneylog.core.sk.page.PageQuery;
import com.moneylog.infra.security.CurrentUser;

@RestController
@RequestMapping("/v1/subscriptions")
public class SubscriptionController {

    private final ListSubscriptionsUseCase list;

    public SubscriptionController(ListSubscriptionsUseCase list) {
        this.list = list;
    }

    @GetMapping
    public SubscriptionResponse.ListResponse list(@CurrentUser UUID userId, @RequestParam(required = false) SubscriptionStatus status,
                                                  @RequestParam(required = false) RecurrenceScope scope, PageQuery page) {
        return SubscriptionResponse.ListResponse.of(list.handle(new ListSubscriptionsQuery(userId, status, scope, page)));
    }
}
