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
        return HttpMethod.POST.name().equals(request.getMethod()) && PATH.equals(decodedPath(request));
    }

    static boolean isAnyVariant(HttpServletRequest request) {
        String path = decodedPath(request);
        if (path == null) {
            return false;
        }
        String normalized = stripTrailingSlashes(path).toLowerCase(Locale.ROOT);
        return normalized.equals(PATH);
    }

    private static String decodedPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null) {
            return null;
        }
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
