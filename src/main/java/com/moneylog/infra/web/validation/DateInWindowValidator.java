package com.moneylog.infra.web.validation;

import java.time.LocalDate;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import com.moneylog.core.sk.time.FinancialDates;

public class DateInWindowValidator implements ConstraintValidator<DateInWindow, LocalDate> {

    @Override
    public boolean isValid(LocalDate value, ConstraintValidatorContext context) {
        return value == null || FinancialDates.isInWindow(value);
    }
}
