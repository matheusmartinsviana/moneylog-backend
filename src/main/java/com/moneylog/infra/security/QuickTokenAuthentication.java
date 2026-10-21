package com.moneylog.infra.security;

import java.util.List;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

public class QuickTokenAuthentication extends AbstractAuthenticationToken {

    public static final String AUTHORITY = "SCOPE_QUICK_ENTRY";

    private final transient QuickTokenPrincipal principal;

    public QuickTokenAuthentication(QuickTokenPrincipal principal) {
        super(List.of(new SimpleGrantedAuthority(AUTHORITY)));
        this.principal = principal;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return "";
    }

    @Override
    public QuickTokenPrincipal getPrincipal() {
        return principal;
    }

    @Override
    public String getName() {
        return principal.userId().toString();
    }
}
