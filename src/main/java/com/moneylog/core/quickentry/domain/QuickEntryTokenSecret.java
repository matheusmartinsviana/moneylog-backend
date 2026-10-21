package com.moneylog.core.quickentry.domain;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;
import java.util.regex.Pattern;

import com.moneylog.core.sk.id.SecureToken;

public final class QuickEntryTokenSecret {

    public static final String PREFIX_MARK = "mlq_";
    public static final int SECRET_BYTES = 32;

    private static final String PREFIX_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final Pattern FORMAT = Pattern.compile("^mlq_[A-Za-z0-9]{8}_[A-Za-z0-9_-]{43}$");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final String value;

    private QuickEntryTokenSecret(String value) {
        this.value = value;
    }

    public static QuickEntryTokenSecret generate() {
        return generate(RANDOM);
    }

    static QuickEntryTokenSecret generate(SecureRandom random) {
        StringBuilder prefix = new StringBuilder(QuickEntryToken.PREFIX_LENGTH);
        for (int i = 0; i < QuickEntryToken.PREFIX_LENGTH; i++) {
            prefix.append(PREFIX_ALPHABET.charAt(random.nextInt(PREFIX_ALPHABET.length())));
        }
        byte[] bytes = new byte[SECRET_BYTES];
        random.nextBytes(bytes);
        String body = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        return new QuickEntryTokenSecret(PREFIX_MARK + prefix + "_" + body);
    }

    public static boolean hasValidFormat(String candidate) {
        return candidate != null && FORMAT.matcher(candidate).matches();
    }

    public static String hashOf(String token) {
        return SecureToken.hash(Objects.requireNonNull(token, "token"));
    }

    public String value() {
        return value;
    }

    public String prefix() {
        return value.substring(PREFIX_MARK.length(), PREFIX_MARK.length() + QuickEntryToken.PREFIX_LENGTH);
    }

    public String hash() {
        return hashOf(value);
    }

    @Override
    public String toString() {
        return "QuickEntryTokenSecret[prefix=" + prefix() + "]";
    }
}
