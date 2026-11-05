package com.transfer.security;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cuenta los inicios de sesión fallidos por IP y bloquea la IP durante un
 * tiempo cuando supera el máximo. Se guarda en memoria: un reinicio lo limpia.
 */
@Service
public class LoginAttemptService {

    private record Attempts(int failures, Instant firstFailure, Instant lockedUntil) {
    }

    private final Map<String, Attempts> attempts = new ConcurrentHashMap<>();
    private final int maxAttempts;
    private final Duration lockDuration;
    private final Clock clock;

    @Autowired
    public LoginAttemptService(
            @Value("${transfer.login.max-attempts:5}") int maxAttempts,
            @Value("${transfer.login.lock-minutes:15}") int lockMinutes) {
        this(maxAttempts, Duration.ofMinutes(lockMinutes), Clock.systemUTC());
    }

    LoginAttemptService(int maxAttempts, Duration lockDuration, Clock clock) {
        this.maxAttempts = maxAttempts;
        this.lockDuration = lockDuration;
        this.clock = clock;
    }

    public void loginFailed(String ip) {
        if (ip == null || maxAttempts <= 0) {
            return;
        }
        Instant now = clock.instant();
        attempts.compute(ip, (key, current) -> {
            // Los fallos caducan pasado el tiempo de bloqueo
            if (current == null || current.firstFailure().plus(lockDuration).isBefore(now)) {
                current = new Attempts(0, now, null);
            }
            int failures = current.failures() + 1;
            Instant lockedUntil = failures >= maxAttempts ? now.plus(lockDuration) : null;
            return new Attempts(failures, current.firstFailure(), lockedUntil);
        });
        purgeOld(now);
    }

    public void loginSucceeded(String ip) {
        if (ip != null) {
            attempts.remove(ip);
        }
    }

    public boolean isBlocked(String ip) {
        if (ip == null) {
            return false;
        }
        Attempts current = attempts.get(ip);
        return current != null && current.lockedUntil() != null && current.lockedUntil().isAfter(clock.instant());
    }

    private void purgeOld(Instant now) {
        if (attempts.size() > 10_000) {
            attempts.entrySet().removeIf(e -> e.getValue().firstFailure().plus(lockDuration).isBefore(now)
                    && (e.getValue().lockedUntil() == null || e.getValue().lockedUntil().isBefore(now)));
        }
    }
}
