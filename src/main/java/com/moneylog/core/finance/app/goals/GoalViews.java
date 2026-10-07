package com.moneylog.core.finance.app.goals;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.moneylog.core.account.domain.Account;
import com.moneylog.core.finance.domain.CashPosition;
import com.moneylog.core.finance.domain.FinancialRecords;
import com.moneylog.core.finance.domain.GoalReserves;
import com.moneylog.core.goal.domain.Goal;
import com.moneylog.core.goal.domain.GoalReservation;
import com.moneylog.core.sk.persistence.Versioned;

/**
 * Monta {@link GoalDetails} sobre os {@link FinancialRecords} carregados uma vez (S-79): a reserva descoberta usa o mesmo
 * {@link GoalReserves} do dashboard e da conta.
 */
public final class GoalViews {

    private GoalViews() {
    }

    public static GoalDetails of(Versioned<Goal> versioned, FinancialRecords records, LocalDate today) {
        GoalReserves reserves = GoalReserves.at(records, CashPosition.at(records, records.ledger(), today));
        return of(versioned, accountNames(records), reserves, today);
    }

    /** Nomes das contas por id (para as reservas). */
    public static Map<UUID, String> accountNames(FinancialRecords records) {
        Map<UUID, String> names = new HashMap<>();
        for (Account account : records.accounts()) {
            names.put(account.id(), account.name());
        }
        return names;
    }

    /** Mesma visão com as reservas e os nomes já calculados (uma vez para a lista inteira). */
    public static GoalDetails of(Versioned<Goal> versioned, Map<UUID, String> accountNames, GoalReserves reserves, LocalDate today) {
        Goal goal = versioned.value();
        List<GoalDetails.ReservationView> views = goal.reservations().stream()
                .sorted(Comparator.comparing(GoalReservation::date).thenComparing(GoalReservation::createdAt).reversed())
                .map(r -> new GoalDetails.ReservationView(r, accountNames.getOrDefault(r.accountId(), null))).toList();
        return new GoalDetails(goal, versioned.version(), versioned.createdAt(), versioned.updatedAt(), today, goal.progress(today),
                goal.status(today), goal.monthlyNeeded(today), goal.isUncovered(reserves::isUncovered, today), views);
    }
}
