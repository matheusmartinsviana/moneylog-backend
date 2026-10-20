package com.moneylog.core.auth.app.usecase;

import java.util.List;

import com.moneylog.core.auth.domain.ManagedUser;

public interface ListUsersUseCase {

    List<ManagedUser> handle();
}
