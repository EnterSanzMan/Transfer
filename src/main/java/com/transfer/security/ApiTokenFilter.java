package com.transfer.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * Autentica las peticiones a /api/** con "Authorization: Bearer &lt;token&gt;".
 * Sin API_TOKEN configurado la API queda desactivada: todas las peticiones reciben 401.
 */
public class ApiTokenFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final int MIN_TOKEN_LENGTH = 32;

    private final byte[] expectedHash;

    public ApiTokenFilter(String token) {
        if (token == null || token.isBlank()) {
            this.expectedHash = null;
            return;
        }
        if (token.trim().length() < MIN_TOKEN_LENGTH) {
            throw new IllegalStateException("API_TOKEN debe tener al menos " + MIN_TOKEN_LENGTH + " caracteres.");
        }
        this.expectedHash = sha256(token.trim());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (expectedHash != null && header != null && header.startsWith(BEARER_PREFIX)
                && MessageDigest.isEqual(expectedHash, sha256(header.substring(BEARER_PREFIX.length()).trim()))) {
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                    "api", null, List.of(new SimpleGrantedAuthority("ROLE_API"))));
            SecurityContextHolder.setContext(context);
        }
        chain.doFilter(request, response);
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
