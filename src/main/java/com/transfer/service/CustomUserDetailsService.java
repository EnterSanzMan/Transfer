package com.transfer.service;

import com.transfer.model.AppUser;
import com.transfer.repository.UserRepository;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.Collections;

@Service
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    public CustomUserDetailsService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        // Los usuarios se guardan en minúsculas (ver AdminController y DataInitializer)
        String normalized = username == null ? "" : username.trim().toLowerCase();
        AppUser appUser = userRepository.findByUsername(normalized)
                .orElseThrow(() -> new UsernameNotFoundException("Usuario no encontrado: " + normalized));

        return new User(
                appUser.getUsername(),
                appUser.getPassword(),
                Collections.singletonList(new SimpleGrantedAuthority(appUser.getRole() != null ? appUser.getRole() : "ROLE_USER"))
        );
    }
}
