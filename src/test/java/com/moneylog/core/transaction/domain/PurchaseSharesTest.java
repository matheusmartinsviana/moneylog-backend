package com.moneylog.core.transaction.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.moneylog.core.sk.money.Money;
import com.moneylog.core.transaction.exception.TransactionExceptions.SharesInvalidException;

class PurchaseSharesTest {

    private static final UUID ANA = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID BETO = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
    private static final Money HUNDRED = Money.of("100.00");

    private static ShareLine bps(UUID person, int bps) {
        return new ShareLine(person, bps, null);
    }

    private static ShareLine equal(UUID person) {
        return new ShareLine(person, null, null);
    }

    private static ShareLine amount(UUID person, String value) {
        return new ShareLine(person, null, Money.of(value));
    }

    @Test
    void bpsFiftyFiftySplitsExactly() {
        InstallmentSplit split = PurchaseShares.create(SplitMode.BPS, List.of(bps(null, 5000), bps(ANA, 5000)), HUNDRED).split(HUNDRED);

        assertThat(split.mine()).isEqualTo(Money.of("50.00"));
        assertThat(split.byPerson()).containsExactly(new PersonAmount(ANA, Money.of("50.00")));
    }

    @Test
    void equalAmongThreeLeavesTheCentToTheOwner() {
        InstallmentSplit split = PurchaseShares.create(SplitMode.EQUAL, List.of(equal(null), equal(ANA), equal(BETO)), HUNDRED).split(HUNDRED);

        assertThat(split.mine()).isEqualTo(Money.of("33.34"));
        assertThat(split.byPerson()).containsExactly(new PersonAmount(ANA, Money.of("33.33")), new PersonAmount(BETO, Money.of("33.33")));
        assertThat(split.mine().add(split.thirdParties())).isEqualTo(HUNDRED);
    }

    @Test
    void bpsThirdsRoundDownAndTheOwnerKeepsTheRest() {
        InstallmentSplit split = PurchaseShares.create(SplitMode.BPS, List.of(bps(ANA, 3333), bps(BETO, 3333), bps(null, 3334)), HUNDRED)
                .split(HUNDRED);

        assertThat(split.mine()).isEqualTo(Money.of("33.34"));
        assertThat(split.thirdParties()).isEqualTo(Money.of("66.66"));
    }

    @Test
    void firstInstallmentWithExtraCentsGivesThemToTheOwner() {
        PurchaseShares shares = PurchaseShares.create(SplitMode.BPS, List.of(bps(null, 5000), bps(ANA, 5000)), Money.of("33.33"));

        InstallmentSplit split = shares.split(Money.of("33.34"));

        assertThat(split.byPerson()).containsExactly(new PersonAmount(ANA, Money.of("16.67")));
        assertThat(split.mine()).isEqualTo(Money.of("16.67"));
    }

    @Test
    void amountModeKeepsThirdPartiesExactAndOwnerAbsorbsTheRemainder() {
        PurchaseShares shares = PurchaseShares.create(SplitMode.AMOUNT, List.of(amount(null, "40.00"), amount(ANA, "60.00")), HUNDRED);

        InstallmentSplit split = shares.split(Money.of("100.01"));

        assertThat(split.byPerson()).containsExactly(new PersonAmount(ANA, Money.of("60.00")));
        assertThat(split.mine()).isEqualTo(Money.of("40.01"));
    }

    @Test
    void invalidSharesAreRefused() {
        assertThatThrownBy(() -> PurchaseShares.create(SplitMode.BPS, List.of(bps(null, 5000), bps(ANA, 4000)), HUNDRED))
                .isInstanceOf(SharesInvalidException.class);
        assertThatThrownBy(() -> PurchaseShares.create(SplitMode.BPS, List.of(bps(ANA, 5000), bps(ANA, 5000)), HUNDRED))
                .isInstanceOf(SharesInvalidException.class);
        assertThatThrownBy(() -> PurchaseShares.create(SplitMode.AMOUNT, List.of(amount(null, "40.00"), amount(ANA, "50.00")), HUNDRED))
                .isInstanceOf(SharesInvalidException.class);
        assertThatThrownBy(() -> PurchaseShares.create(null, List.of(equal(ANA)), HUNDRED)).isInstanceOf(SharesInvalidException.class);
        assertThatThrownBy(() -> PurchaseShares.create(SplitMode.EQUAL, List.of(bps(ANA, 100)), HUNDRED))
                .isInstanceOf(SharesInvalidException.class);
    }

    @Test
    void onlyTheOwnerOrNoLinesMeansNoSharing() {
        assertThat(PurchaseShares.create(SplitMode.BPS, List.of(bps(null, 10000)), HUNDRED)).isNull();
        assertThat(PurchaseShares.create(null, List.of(), HUNDRED)).isNull();
    }
}
