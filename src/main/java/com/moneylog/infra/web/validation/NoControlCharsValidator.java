package com.moneylog.infra.web.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import com.moneylog.core.sk.text.PlainText;

public class NoControlCharsValidator implements ConstraintValidator<NoControlChars, String> {

    private boolean allowLineBreaks;

    @Override
    public void initialize(NoControlChars annotation) {
        this.allowLineBreaks = annotation.allowLineBreaks();
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        return !PlainText.hasControlCharacters(value, allowLineBreaks);
    }
}
