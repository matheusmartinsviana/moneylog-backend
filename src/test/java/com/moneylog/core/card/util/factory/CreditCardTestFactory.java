package com.moneylog.core.card.util.factory;

import static com.moneylog.core.util.Ids.USER_ID;

import java.time.LocalDate;
import java.util.UUID;

import com.moneylog.core.account.util.factory.AccountTestFactory;
import com.moneylog.core.card.domain.CreditCard;
import com.moneylog.core.sk.money.Money;

public final class CreditCardTestFactory {

    public static final UUID ROXINHO_ID = UUID.fromString("00000000-0000-0000-0000-00000000d001");
    public static final LocalDate CREATED_ON = LocalDate.of(2026, 9, 1);
    public static final Money LIMIT = Money.of("10000.00");

    private CreditCardTestFactory() {
    }

    public static CreditCard umCartao() {
        return umCartao(25, 5);
    }

    public static CreditCard umCartao(int closingDay, int dueDay) {
        return umCartaoComLimite(LIMIT, closingDay, dueDay);
    }

    public static CreditCard umCartaoComLimite(Money limit, int closingDay, int dueDay) {
        return CreditCard.create(ROXINHO_ID, USER_ID, "Roxinho", "Nubank", limit, closingDay, dueDay,
                AccountTestFactory.NUBANK_ID, CREATED_ON);
    }

    public static CreditCard umCartaoArquivado() {
        CreditCard card = umCartao();
        card.archive();
        return card;
    }
}
