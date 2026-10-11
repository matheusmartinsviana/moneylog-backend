package com.moneylog.core.insights.domain;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

import com.moneylog.core.sk.money.Money;

public final class SubscriptionDetector {

    public static final int MIN_OCCURRENCES = 3;
    public static final int MIN_INTERVAL = 28;
    public static final int MAX_INTERVAL = 32;
    public static final int ACTIVE_WITHIN_DAYS = 45;
    public static final int MIN_DESCRIPTION_LENGTH = 3;
    public static final int MAX_RESULTS = 3;
    private static final BigDecimal TOLERANCE = new BigDecimal("0.10");
    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern NOT_LETTER = Pattern.compile("[^\\p{L}]+");

    private SubscriptionDetector() {
    }

    public record Line(LocalDate date, String description, Money amount) {
    }

    public record Detected(String key, String description, Money typicalAmount, Money annualCost, int occurrences, LocalDate lastDate,
                           int intervalDays) {
    }

    public static String normalize(String description) {
        if (description == null) {
            return "";
        }
        String noMarks = MARKS.matcher(Normalizer.normalize(description, Normalizer.Form.NFD)).replaceAll("");
        return NOT_LETTER.matcher(noMarks.toLowerCase(Locale.ROOT)).replaceAll(" ").strip();
    }

    public static List<Detected> detect(List<Line> lines, Set<String> registered, LocalDate asOf) {
        Map<String, List<Line>> groups = new TreeMap<>();
        for (Line line : lines) {
            String key = normalize(line.description());
            if (key.length() >= MIN_DESCRIPTION_LENGTH && line.amount().isPositive()) {
                groups.computeIfAbsent(key, k -> new ArrayList<>()).add(line);
            }
        }
        List<Detected> found = new ArrayList<>();
        groups.forEach((key, group) -> {
            if (group.size() >= MIN_OCCURRENCES && !registered.contains(key)) {
                detectOne(key, group, asOf).ifPresent(found::add);
            }
        });
        return found.stream().sorted(Comparator.comparing(Detected::annualCost).reversed().thenComparing(Detected::key))
                .limit(MAX_RESULTS).toList();
    }

    private static java.util.Optional<Detected> detectOne(String key, List<Line> group, LocalDate asOf) {
        List<Line> sorted = group.stream().sorted(Comparator.comparing(Line::date)).toList();
        List<BigDecimal> gaps = new ArrayList<>();
        for (int i = 1; i < sorted.size(); i++) {
            gaps.add(BigDecimal.valueOf(ChronoUnit.DAYS.between(sorted.get(i - 1).date(), sorted.get(i).date())));
        }
        int interval = Stats.median(gaps).setScale(0, java.math.RoundingMode.HALF_UP).intValueExact();
        if (interval < MIN_INTERVAL || interval > MAX_INTERVAL) {
            return java.util.Optional.empty();
        }
        Money typical = Fmt.money(Stats.median(sorted.stream().map(l -> Fmt.dec(l.amount())).toList()));
        BigDecimal band = Fmt.dec(typical).multiply(TOLERANCE);
        for (Line line : sorted) {
            if (Fmt.dec(line.amount()).subtract(Fmt.dec(typical)).abs().compareTo(band) > 0) {
                return java.util.Optional.empty();
            }
        }
        LocalDate last = sorted.get(sorted.size() - 1).date();
        if (ChronoUnit.DAYS.between(last, asOf) > ACTIVE_WITHIN_DAYS) {
            return java.util.Optional.empty();
        }
        String shown = sorted.get(sorted.size() - 1).description().strip();
        return java.util.Optional.of(new Detected(key, shown, typical, typical.times(12), sorted.size(), last, interval));
    }
}
