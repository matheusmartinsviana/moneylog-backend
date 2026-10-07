package com.moneylog.core.debt.util.factory;

import static com.moneylog.core.util.Ids.USER_ID;

import java.util.UUID;

import com.moneylog.core.debt.domain.Person;

public final class PersonTestFactory {

    public static final UUID JOAO_ID = UUID.fromString("00000000-0000-0000-0000-0000000f0001");
    public static final UUID PEDRO_ID = UUID.fromString("00000000-0000-0000-0000-0000000f0002");

    private PersonTestFactory() {
    }

    public static Person umJoao() {
        return Person.create(JOAO_ID, USER_ID, "João", null);
    }

    public static Person umPedro() {
        return Person.create(PEDRO_ID, USER_ID, "Pedro", "colega");
    }
}
