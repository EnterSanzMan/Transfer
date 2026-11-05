package com.transfer.config;

import com.transfer.model.AppUser;
import com.transfer.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;

/**
 * Crea el usuario administrador inicial en el primer arranque.
 * No hay clave por defecto: se toma de INITIAL_ADMIN_PASS o, si está vacía,
 * se genera una aleatoria que se escribe una sola vez en el log.
 */
@Component
public class DataInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
    private static final int GENERATED_LENGTH = 16;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final String initialUsername;
    private final String initialPassword;

    public DataInitializer(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            @Value("${transfer.initial-admin.username:admin}") String initialUsername,
            @Value("${transfer.initial-admin.password:}") String initialPassword) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.initialUsername = initialUsername.trim().toLowerCase();
        this.initialPassword = initialPassword;
    }

    @Override
    public void run(String... args) {
        var adminOpt = userRepository.findByUsername(initialUsername);
        if (adminOpt.isPresent()) {
            AppUser admin = adminOpt.get();
            if (!"ROLE_ADMIN".equals(admin.getRole())) {
                admin.setRole("ROLE_ADMIN");
                userRepository.save(admin);
                log.info("Usuario '{}' actualizado a ROLE_ADMIN.", initialUsername);
            }
            return;
        }

        boolean generated = initialPassword == null || initialPassword.isBlank();
        String password = generated ? generatePassword() : initialPassword;
        userRepository.save(new AppUser(initialUsername, passwordEncoder.encode(password), "ROLE_ADMIN"));

        if (generated) {
            log.warn("""

                    ============================================================
                     Usuario administrador inicial creado
                       Usuario: {}
                       Clave:   {}
                     Esta clave no se volverá a mostrar. Cámbiala al entrar,
                     en "Administrar usuarios".
                    ============================================================""",
                    initialUsername, password);
        } else {
            log.info("Usuario administrador inicial '{}' creado con la clave de INITIAL_ADMIN_PASS.", initialUsername);
        }
    }

    private static String generatePassword() {
        SecureRandom random = new SecureRandom();
        StringBuilder sb = new StringBuilder(GENERATED_LENGTH);
        for (int i = 0; i < GENERATED_LENGTH; i++) {
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
}
