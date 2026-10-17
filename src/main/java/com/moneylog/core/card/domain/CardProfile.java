package com.moneylog.core.card.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import com.moneylog.core.card.exception.CardExceptions.CardBrandTooLongException;
import com.moneylog.core.card.exception.CardExceptions.CardLastDigitsInvalidException;
import com.moneylog.core.card.exception.CardExceptions.InvalidBillingDayException;

public record CardProfile(Boolean autoDebit, String brand, Integer bestPurchaseDay, List<String> lastDigits) {

    public static final int BRAND_MAX_LENGTH = 40;
    public static final int MAX_LAST_DIGITS = 5;
    public static final CardProfile EMPTY = new CardProfile(null, null, null, List.of());

    private static final Pattern DIGITS = Pattern.compile("^\\d{4}$");

    public CardProfile {
        brand = cleanBrand(brand);
        if (bestPurchaseDay != null && (bestPurchaseDay < 1 || bestPurchaseDay > 31)) {
            throw new InvalidBillingDayException("melhor dia de compra", bestPurchaseDay);
        }
        lastDigits = cleanDigits(lastDigits);
    }

    public boolean hasLastDigits(String digits) {
        return lastDigits.contains(digits);
    }

    public static CardProfile restore(Boolean autoDebit, String brand, Integer bestPurchaseDay, String stored) {
        List<String> digits = stored == null || stored.isBlank() ? List.of() : List.of(stored.split(","));
        return new CardProfile(autoDebit, brand, bestPurchaseDay, digits);
    }

    public String storedLastDigits() {
        return String.join(",", lastDigits);
    }

    private static String cleanBrand(String brand) {
        if (brand == null || brand.isBlank()) {
            return null;
        }
        String trimmed = brand.strip();
        if (trimmed.length() > BRAND_MAX_LENGTH) {
            throw new CardBrandTooLongException(BRAND_MAX_LENGTH);
        }
        return trimmed;
    }

    private static List<String> cleanDigits(List<String> digits) {
        if (digits == null) {
            return List.of();
        }
        if (digits.size() > MAX_LAST_DIGITS) {
            throw new CardLastDigitsInvalidException("no máximo " + MAX_LAST_DIGITS + " finais por cartão.");
        }
        List<String> result = new ArrayList<>();
        for (String digit : digits) {
            if (digit == null || !DIGITS.matcher(digit).matches()) {
                throw new CardLastDigitsInvalidException("cada final tem 4 dígitos.");
            }
            if (result.contains(digit)) {
                throw new CardLastDigitsInvalidException("final repetido: " + digit + ".");
            }
            result.add(digit);
        }
        return List.copyOf(result);
    }
}
