package com.moneylog.infra.web;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

import org.springframework.security.web.firewall.RequestRejectedException;
import org.springframework.security.web.firewall.RequestRejectedHandler;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public class QueryStringGuardFilter extends OncePerRequestFilter {

    private final RequestRejectedHandler rejectedHandler;

    public QueryStringGuardFilter(RequestRejectedHandler rejectedHandler) {
        this.rejectedHandler = rejectedHandler;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getQueryString() == null || request.getQueryString().indexOf('%') < 0;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!validPercentEncoding(request.getQueryString())) {
            rejectedHandler.handle(request, response, new RequestRejectedException("Query string com percent-encoding inválido."));
            return;
        }
        chain.doFilter(request, response);
    }

    static boolean validPercentEncoding(String query) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int i = 0; i < query.length(); i++) {
            char c = query.charAt(i);
            if (c == '%') {
                if (i + 2 >= query.length()) {
                    return false;
                }
                int hi = Character.digit(query.charAt(i + 1), 16);
                int lo = Character.digit(query.charAt(i + 2), 16);
                if (hi < 0 || lo < 0 || query.charAt(i + 1) > 'f' || query.charAt(i + 2) > 'f') {
                    return false;
                }
                bytes.write(hi * 16 + lo);
                i += 2;
            } else {
                if (Character.isSurrogate(c)) {
                    continue;
                }
                byte[] raw = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
                bytes.write(raw, 0, raw.length);
            }
        }
        try {
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray()));
            return true;
        } catch (CharacterCodingException e) {
            return false;
        }
    }
}
