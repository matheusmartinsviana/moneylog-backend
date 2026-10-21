package com.moneylog.infra.security;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpMethod;
import org.springframework.web.util.UriUtils;

final class QuickEntryRoute {

    static final String PATH = "/v1/quick-entries";

    private QuickEntryRoute() {
    }

    static boolean isTokenRoute(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null || uri.indexOf(';') >= 0 || !HttpMethod.POST.name().equals(request.getMethod())) {
            return false;
        }
        String decoded = decode(uri);
        return decoded != null && PATH.equals(decoded);
    }

    static boolean isAnyVariant(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null) {
            return false;
        }
        String decoded = decode(withoutPathParameters(uri));
        if (decoded == null) {
            return true;
        }
        return stripTrailingSlashes(decoded).toLowerCase(Locale.ROOT).equals(PATH);
    }

    private static String withoutPathParameters(String uri) {
        String[] segments = uri.split("/", -1);
        for (int i = 0; i < segments.length; i++) {
            int semicolon = segments[i].indexOf(';');
            if (semicolon >= 0) {
                segments[i] = segments[i].substring(0, semicolon);
            }
        }
        return String.join("/", segments);
    }

    private static String decode(String uri) {
        try {
            return UriUtils.decode(uri, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String stripTrailingSlashes(String path) {
        int end = path.length();
        while (end > 1 && path.charAt(end - 1) == '/') {
            end--;
        }
        return path.substring(0, end);
    }
}
