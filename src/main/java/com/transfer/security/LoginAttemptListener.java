package com.transfer.security;

import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AbstractAuthenticationFailureEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.web.authentication.WebAuthenticationDetails;
import org.springframework.stereotype.Component;

/**
 * Traslada los eventos de login de Spring Security al contador de intentos.
 */
@Component
public class LoginAttemptListener {

    private final LoginAttemptService loginAttemptService;

    public LoginAttemptListener(LoginAttemptService loginAttemptService) {
        this.loginAttemptService = loginAttemptService;
    }

    @EventListener
    public void onFailure(AbstractAuthenticationFailureEvent event) {
        loginAttemptService.loginFailed(remoteAddress(event.getAuthentication().getDetails()));
    }

    @EventListener
    public void onSuccess(AuthenticationSuccessEvent event) {
        loginAttemptService.loginSucceeded(remoteAddress(event.getAuthentication().getDetails()));
    }

    private static String remoteAddress(Object details) {
        return details instanceof WebAuthenticationDetails web ? web.getRemoteAddress() : null;
    }
}
