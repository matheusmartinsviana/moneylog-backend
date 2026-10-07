package com.moneylog.qa;

import java.util.ArrayList;
import java.util.List;

import tools.jackson.databind.JsonNode;

/**
 * T61 (QA): as invariantes financeiras do cartão, conferidas a partir da API e do SQL do razão. Devolve a lista de
 * violações (vazia = tudo certo) para o teste decidir a mensagem.
 */
final class CardInvariants {

    private CardInvariants() {
    }

    static List<String> check(CardQaWorld.Actor a, String cardId) throws Exception {
        List<String> v = new ArrayList<>();
        if (a.ledgerSum() != 0) {
            v.add("razão não fecha em zero: " + a.ledgerSum());
        }
        if (a.unbalancedGroups() != 0) {
            v.add("grupos desbalanceados: " + a.unbalancedGroups());
        }
        List<JsonNode> invoices = a.invoicesOf(cardId);
        JsonNode card = a.getOk("/v1/cards/" + cardId);
        long book = a.cardBook(cardId);
        long sumOutstanding = 0;
        long totalCommitted = 0;
        for (JsonNode i : invoices) {
            long outstanding = CardQaWorld.cents(i.get("outstanding"));
            String status = i.get("status").asString();
            if (outstanding < 0) {
                v.add("outstanding negativo na fatura " + i.get("closingDate") + ": " + outstanding);
            }
            if (CardQaWorld.cents(i.get("creditCarry")) < 0 || CardQaWorld.cents(i.get("paid")) < 0) {
                v.add("paid/creditCarry negativo na fatura " + i.get("closingDate"));
            }
            if ("PAID".equals(status) && outstanding != 0) {
                v.add("fatura PAID com outstanding " + outstanding);
            }
            if (!"PAID".equals(status) && !"FUTURE".equals(status) && outstanding > 0) {
                totalCommitted += outstanding;
            }
            sumOutstanding += outstanding;
        }
        long lastCarry = invoices.isEmpty() ? 0 : CardQaWorld.cents(invoices.get(0).get("creditCarry"));
        if (sumOutstanding - lastCarry != -book) {
            v.add("Σ outstanding (" + sumOutstanding + ") − creditCarry da última (" + lastCarry + ") != −saldo CARD (" + (-book) + ")");
        }
        long limit = CardQaWorld.cents(card.get("limit"));
        long available = CardQaWorld.cents(card.get("availableLimit"));
        if (available != limit + book) {
            v.add("availableLimit " + available + " != limite + livro CARD " + (limit + book));
        }
        // totalCommitted do cartão = Σ outstanding das faturas nem PAID nem FUTURE (S-129); a corrente conta
        JsonNode current = card.get("currentInvoice");
        long expectedTotalCommitted = totalCommitted;
        boolean currentListed = invoices.stream().anyMatch(i -> i.get("id").asString().equals(current.get("id").asString()));
        if (!currentListed && !"FUTURE".equals(current.get("status").asString()) && !"PAID".equals(current.get("status").asString())) {
            expectedTotalCommitted += CardQaWorld.cents(current.get("outstanding"));
        }
        if (CardQaWorld.cents(card.get("totalCommitted")) != expectedTotalCommitted) {
            v.add("Card.totalCommitted " + card.get("totalCommitted").get("amount") + " != Σ outstanding não PAID/FUTURE "
                    + CardQaWorld.money(expectedTotalCommitted));
        }
        JsonNode future = a.getOk("/v1/cards/" + cardId + "/future-installments");
        if (!CardQaWorld.money(CardQaWorld.cents(future.get("total"))).equals(card.get("committedFuture").get("amount").asString())) {
            v.add("committedFuture do cartão (" + card.get("committedFuture").get("amount").asString() + ") != future-installments.total ("
                    + future.get("total").get("amount").asString() + ")");
        }
        return v;
    }
}
