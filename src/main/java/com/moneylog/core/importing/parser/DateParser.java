package com.moneylog.core.importing.parser;

import java.time.LocalDate;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DateParser {

    private static final Pattern BR = Pattern.compile("^(\\d{1,2})[/.\\-](\\d{1,2})[/.\\-](\\d{4}|\\d{2})(?:[ T].*)?$");
    private static final Pattern ISO = Pattern.compile("^(\\d{4})-(\\d{2})-(\\d{2})(?:[ T].*)?$");
    private static final Pattern COMPACT = Pattern.compile("^(\\d{4})(\\d{2})(\\d{2})(?:\\d{0,6}.*)?$");

    private DateParser() {
    }

    public static Optional<LocalDate> parse(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String text = raw.strip();
        Matcher iso = ISO.matcher(text);
        if (iso.matches()) {
            return build(iso.group(1), iso.group(2), iso.group(3));
        }
        Matcher br = BR.matcher(text);
        if (br.matches()) {
            String year = br.group(3);
            if (year.length() == 2) {
                year = "20" + year;
            }
            return build(year, br.group(2), br.group(1));
        }
        Matcher compact = COMPACT.matcher(text);
        if (compact.matches()) {
            return build(compact.group(1), compact.group(2), compact.group(3));
        }
        return Optional.empty();
    }

    private static Optional<LocalDate> build(String year, String month, String day) {
        try {
            return Optional.of(LocalDate.of(Integer.parseInt(year), Integer.parseInt(month), Integer.parseInt(day)));
        } catch (java.time.DateTimeException | NumberFormatException e) {
            return Optional.empty();
        }
    }
}
