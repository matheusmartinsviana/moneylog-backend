package com.moneylog.infra.security;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

public class QuickTokenAuthenticationFilter extends OncePerRequestFilter {

    private final QuickTokenAuthenticator authenticator;
    private final HandlerExceptionResolver resolver;

    public QuickTokenAuthenticationFilter(QuickTokenAuthenticator authenticator, HandlerExceptionResolver resolver) {
        this.authenticator = authenticator;
        this.resolver = resolver;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication;
        try {
            authentication = authenticator.authenticate(request);
        } catch (RuntimeException e) {
            SecurityContextHolder.clearContext();
            resolver.resolveException(request, response, null, e);
            return;
        }
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        chain.doFilter(request, response);
    }
}
