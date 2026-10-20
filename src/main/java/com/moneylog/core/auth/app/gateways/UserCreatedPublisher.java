package com.moneylog.core.auth.app.gateways;

import com.moneylog.core.auth.app.UserCreated;

public interface UserCreatedPublisher {

    void publish(UserCreated event);
}
