package com.transfer.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Rechaza el envío del formulario de login si la IP está bloqueada por
 * demasiados intentos fallidos, sin llegar a comprobar la clave.
 */
public class LoginThrottleFilter extends OncePerRequestFilter {

    private final LoginAttemptService loginAttemptService;

    public LoginThrottleFilter(LoginAttemptService loginAttemptService) {
        this.loginAttemptService = loginAttemptService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !("POST".equals(request.getMethod()) && "/login".equals(path));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (loginAttemptService.isBlocked(request.getRemoteAddr())) {
            response.sendRedirect(request.getContextPath() + "/login?blocked=true");
            return;
        }
        chain.doFilter(request, response);
    }
}
