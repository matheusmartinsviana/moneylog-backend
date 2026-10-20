package com.moneylog.infra.invitation;

import org.springframework.context.ApplicationEventPublisher;

import com.moneylog.core.auth.app.UserCreated;
import com.moneylog.core.auth.app.gateways.UserCreatedPublisher;

public class SpringUserCreatedPublisher implements UserCreatedPublisher {

    private final ApplicationEventPublisher publisher;

    public SpringUserCreatedPublisher(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    @Override
    public void publish(UserCreated event) {
        publisher.publishEvent(event);
    }
}
