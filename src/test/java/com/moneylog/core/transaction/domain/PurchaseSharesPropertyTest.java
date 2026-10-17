package com.moneylog.core.transaction.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.moneylog.core.sk.money.Money;

@DisplayName("Divisão de parcela entre pessoas: propriedade de 200 cenários (P-5, ADR-022)")
class PurchaseSharesPropertyTest {

    private static final int SCENARIOS = 200;

    @Test
    @DisplayName("minha parte + terceiros = parcela; terceiros arredondados para baixo e exatos nos três modos; o resto de centavo é sempre do dono e nunca negativo")
    void divisaoFechaAParcela() {
        Random random = new Random(20261015L);
        for (int index = 0; index < SCENARIOS; index++) {
            final int scenario = index;
            long cents = 1 + random.nextInt(50_000_000);
            Money installment = Money.ofCents(cents, com.moneylog.core.sk.money.CurrencyCode.BRL);
            int thirds = 1 + random.nextInt(4);
            List<UUID> people = new ArrayList<>();
            for (int i = 0; i < thirds; i++) {
                people.add(new UUID(0, 100 + i));
            }
            switch (scenario % 3) {
                case 0 -> {
                    int remaining = 10_000;
                    List<ShareLine> lines = new ArrayList<>();
                    List<Integer> bps = new ArrayList<>();
                    for (int i = 0; i < thirds; i++) {
                        int max = remaining - (thirds - i);
                        int part = 1 + random.nextInt(Math.max(1, Math.min(max, 9_500 / thirds)));
                        remaining -= part;
                        bps.add(part);
                        lines.add(new ShareLine(people.get(i), part, null));
                    }
                    lines.add(new ShareLine(null, remaining, null));
                    InstallmentSplit split = PurchaseShares.create(SplitMode.BPS, lines, installment).split(installment);
                    for (int i = 0; i < thirds; i++) {
                        UUID person = people.get(i);
                        long expected = cents * bps.get(i) / 10_000;
                        long actual = split.byPerson().stream().filter(p -> p.personId().equals(person)).mapToLong(p -> p.amount().cents()).sum();
                        assertThat(actual).as("cenário %d BPS pessoa %s", scenario, person).isEqualTo(expected);
                    }
                    assertCloses(scenario, installment, split);
                }
                case 1 -> {
                    List<ShareLine> lines = new ArrayList<>();
                    lines.add(new ShareLine(null, null, null));
                    people.forEach(p -> lines.add(new ShareLine(p, null, null)));
                    InstallmentSplit split = PurchaseShares.create(SplitMode.EQUAL, lines, installment).split(installment);
                    long each = cents / (thirds + 1);
                    split.byPerson().forEach(p -> assertThat(p.amount().cents()).as("cenário %d EQUAL", scenario).isEqualTo(each));
                    assertThat(split.mine().cents()).isEqualTo(cents - each * thirds);
                    assertThat(split.mine().cents()).as("o dono fica com pelo menos a fatia igual").isGreaterThanOrEqualTo(each);
                    assertCloses(scenario, installment, split);
                }
                default -> {
                    long left = cents;
                    List<ShareLine> lines = new ArrayList<>();
                    for (int i = 0; i < thirds && left > 1; i++) {
                        long part = 1 + random.nextInt((int) Math.max(1, left / 2));
                        lines.add(new ShareLine(people.get(i), null, Money.ofCents(part, com.moneylog.core.sk.money.CurrencyCode.BRL)));
                        left -= part;
                    }
                    lines.add(new ShareLine(null, null, Money.ofCents(left, com.moneylog.core.sk.money.CurrencyCode.BRL)));
                    InstallmentSplit split = PurchaseShares.create(SplitMode.AMOUNT, lines, installment).split(installment);
                    assertThat(split.mine().cents()).as("cenário %d AMOUNT", scenario).isEqualTo(left);
                    assertCloses(scenario, installment, split);
                }
            }
        }
    }

    private static void assertCloses(int scenario, Money installment, InstallmentSplit split) {
        assertThat(split.mine().add(split.thirdParties())).as("cenário %d: mine + terceiros = parcela", scenario).isEqualTo(installment);
        assertThat(split.mine().isNegative()).isFalse();
        split.byPerson().forEach(p -> assertThat(p.amount().isPositive()).isTrue());
        assertThat(split.thirdParties().cents()).isEqualTo(split.byPerson().stream().mapToLong(p -> p.amount().cents()).sum());
    }
}
