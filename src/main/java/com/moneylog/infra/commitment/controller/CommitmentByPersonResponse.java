package com.moneylog.infra.commitment.controller;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.moneylog.core.commitment.app.CommitmentByPersonView;
import com.moneylog.core.commitment.domain.PersonTotals;
import com.moneylog.core.commitment.domain.ScheduleItemType;
import com.moneylog.core.sk.money.Money;

public record CommitmentByPersonResponse(UUID commitmentId, String name, List<Block> people, List<Integer> undefinedItems) {

    public record Block(UUID personId, List<Item> items, Money paid, Money pending, Money total) {
    }

    public record Item(int position, String label, ScheduleItemType type, LocalDate dueDate, Money amount, boolean paid, boolean estimated) {
    }

    public static CommitmentByPersonResponse of(CommitmentByPersonView view) {
        return new CommitmentByPersonResponse(view.commitment().id(), view.commitment().name(),
                view.people().stream().map(CommitmentByPersonResponse::block).toList(), view.undefinedItems());
    }

    private static Block block(PersonTotals p) {
        return new Block(p.personId(), p.items().stream().map(i -> new Item(i.position(), i.label(), i.type(), i.dueDate(), i.amount(),
                i.paid(), i.estimated())).toList(), p.paid(), p.pending(), p.total());
    }
}
