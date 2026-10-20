package com.moneylog.infra.auth.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.moneylog.core.auth.app.usecase.DisableUserUseCase;
import com.moneylog.core.auth.app.usecase.DisableUserUseCase.DisableUserCommand;
import com.moneylog.core.auth.app.usecase.EnableUserUseCase;
import com.moneylog.core.auth.app.usecase.EnableUserUseCase.EnableUserCommand;
import com.moneylog.core.auth.app.usecase.ListUsersUseCase;

@RestController
@RequestMapping("/v1/users")
public class UserAdminController {

    private final ListUsersUseCase listUsers;
    private final DisableUserUseCase disableUser;
    private final EnableUserUseCase enableUser;

    public UserAdminController(ListUsersUseCase listUsers, DisableUserUseCase disableUser, EnableUserUseCase enableUser) {
        this.listUsers = listUsers;
        this.disableUser = disableUser;
        this.enableUser = enableUser;
    }

    @GetMapping
    public ResponseEntity<List<ManagedUserResponse>> list() {
        return noStore(listUsers.handle().stream().map(ManagedUserResponse::of).toList());
    }

    @PostMapping("/{id}/disable")
    public ResponseEntity<ManagedUserResponse> disable(@PathVariable UUID id) {
        return noStore(ManagedUserResponse.of(disableUser.handle(new DisableUserCommand(id))));
    }

    @PostMapping("/{id}/enable")
    public ResponseEntity<ManagedUserResponse> enable(@PathVariable UUID id) {
        return noStore(ManagedUserResponse.of(enableUser.handle(new EnableUserCommand(id))));
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
