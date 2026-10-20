package com.moneylog.infra.mail.config;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public final class MailProperties {

    public static final String NONE = "none";
    public static final String LOG = "log";
    public static final String SES = "ses";
    public static final String FAKE = "fake";
    public static final String DEFAULT_APP_BASE_URL = "https://moneylog.mviana.tech";
    private static final String INVALID_BASE_URL = "MAIL_APP_BASE_URL deve ser uma URL https sem usuário, query ou fragmento (http só em localhost fora de produção).";
    private static final Set<String> KNOWN = Set.of(NONE, LOG, SES, FAKE);
    private static final Pattern ADDRESS = Pattern.compile("[^\\s@<>\",;]+@[^\\s@<>\",;]+");
    private static final Pattern REGION = Pattern.compile("[a-z]{2}(-[a-z]+)+-\\d");

    private final String provider;
    private final String from;
    private final String replyTo;
    private final String sesRegion;
    private final String sesConfigurationSet;
    private final String appBaseUrl;

    public MailProperties(String provider, String from, String replyTo, String sesRegion, String sesConfigurationSet, String appBaseUrl) {
        this(provider, from, replyTo, sesRegion, sesConfigurationSet, appBaseUrl, true);
    }

    public MailProperties(String provider, String from, String replyTo, String sesRegion, String sesConfigurationSet, String appBaseUrl,
                          boolean production) {
        String normalized = provider == null || provider.isBlank() ? NONE : provider.strip().toLowerCase(Locale.ROOT);
        if (!KNOWN.contains(normalized)) {
            throw new IllegalStateException("MAIL_PROVIDER inválido. Use none, log ou ses (veja .env.example).");
        }
        this.provider = normalized;
        this.from = strip(from);
        this.replyTo = strip(replyTo);
        this.sesRegion = strip(sesRegion);
        this.sesConfigurationSet = strip(sesConfigurationSet);
        this.appBaseUrl = stripTrailingSlash(strip(appBaseUrl).isEmpty() ? DEFAULT_APP_BASE_URL : strip(appBaseUrl));
        if (SES.equals(normalized)) {
            if (this.from.isEmpty()) {
                throw new IllegalStateException("MAIL_PROVIDER=ses exige MAIL_FROM (veja .env.example). Sem ele, use MAIL_PROVIDER=none.");
            }
            if (this.sesRegion.isEmpty()) {
                throw new IllegalStateException("MAIL_PROVIDER=ses exige MAIL_SES_REGION (veja .env.example). Sem ele, use MAIL_PROVIDER=none.");
            }
            if (!REGION.matcher(this.sesRegion).matches()) {
                throw new IllegalStateException("MAIL_SES_REGION inválida (exemplo: us-east-1).");
            }
        }
        if (!this.from.isEmpty() && !ADDRESS.matcher(this.from).matches()) {
            throw new IllegalStateException("MAIL_FROM deve ser um único endereço de e-mail.");
        }
        if (!this.replyTo.isEmpty() && !ADDRESS.matcher(this.replyTo).matches()) {
            throw new IllegalStateException("MAIL_REPLY_TO deve ser um único endereço de e-mail.");
        }
        validateBaseUrl(this.appBaseUrl, production);
    }

    private static void validateBaseUrl(String value, boolean production) {
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException e) {
            throw new IllegalStateException(INVALID_BASE_URL);
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (host == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalStateException(INVALID_BASE_URL);
        }
        boolean https = "https".equalsIgnoreCase(scheme);
        boolean localHttp = "http".equalsIgnoreCase(scheme) && !production && ("localhost".equals(host) || "127.0.0.1".equals(host));
        if (!https && !localHttp) {
            throw new IllegalStateException(INVALID_BASE_URL);
        }
    }

    public String provider() {
        return provider;
    }

    public String from() {
        return from;
    }

    public String replyTo() {
        return replyTo;
    }

    public String sesRegion() {
        return sesRegion;
    }

    public String sesConfigurationSet() {
        return sesConfigurationSet;
    }

    public String appBaseUrl() {
        return appBaseUrl;
    }

    private static String strip(String value) {
        return value == null ? "" : value.strip();
    }

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
