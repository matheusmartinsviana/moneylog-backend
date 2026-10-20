package com.moneylog.infra.invitation.controller;

import java.io.IOException;

import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public class InvitationNoStoreFilter extends OncePerRequestFilter {

    private static final String PREFIX = "/v1/invitations";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !(path.equals(PREFIX) || path.startsWith(PREFIX + "/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader(HttpHeaders.CACHE_CONTROL, InvitationController.NO_STORE);
        chain.doFilter(request, response);
    }
}
