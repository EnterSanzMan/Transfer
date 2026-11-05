package com.transfer.controller;

import com.transfer.model.AppUser;
import com.transfer.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.security.Principal;
import java.util.List;

@Controller
@RequestMapping("/admin")
public class AdminController {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public AdminController(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @GetMapping("/users")
    public String listUsers(Model model) {
        List<AppUser> users = userRepository.findAll();
        model.addAttribute("users", users);
        return "admin-users";
    }

    @PostMapping("/users/add")
    public String addUser(
            @RequestParam String username,
            @RequestParam String password,
            @RequestParam String role,
            RedirectAttributes redirectAttributes) {

        username = username.trim().toLowerCase();

        if (username.isEmpty() || password.isEmpty()) {
            redirectAttributes.addFlashAttribute("errorMessage", "El nombre de usuario y la contraseña son obligatorios.");
            return "redirect:/admin/users";
        }

        if (userRepository.findByUsername(username).isPresent()) {
            redirectAttributes.addFlashAttribute("errorMessage", "Ya existe un usuario con el nombre '" + username + "'.");
            return "redirect:/admin/users";
        }

        if (!"ROLE_ADMIN".equals(role) && !"ROLE_USER".equals(role)) {
            role = "ROLE_USER";
        }

        AppUser newUser = new AppUser(username, passwordEncoder.encode(password), role);
        userRepository.save(newUser);
        redirectAttributes.addFlashAttribute("successMessage", "Usuario '" + username + "' creado correctamente.");
        return "redirect:/admin/users";
    }

    @PostMapping("/users/{id}/role")
    public String changeRole(
            @PathVariable Long id,
            @RequestParam String role,
            RedirectAttributes redirectAttributes) {

        AppUser user = userRepository.findById(id).orElse(null);
        if (user == null) {
            redirectAttributes.addFlashAttribute("errorMessage", "Usuario no encontrado.");
            return "redirect:/admin/users";
        }

        if (!"ROLE_ADMIN".equals(role) && !"ROLE_USER".equals(role)) {
            role = "ROLE_USER";
        }

        user.setRole(role);
        userRepository.save(user);
        redirectAttributes.addFlashAttribute("successMessage",
                "Rol de '" + user.getUsername() + "' cambiado a " + (role.equals("ROLE_ADMIN") ? "Admin" : "Usuario") + ".");
        return "redirect:/admin/users";
    }

    @PostMapping("/users/{id}/password")
    public String changePassword(
            @PathVariable Long id,
            @RequestParam String newPassword,
            RedirectAttributes redirectAttributes) {

        AppUser user = userRepository.findById(id).orElse(null);
        if (user == null) {
            redirectAttributes.addFlashAttribute("errorMessage", "Usuario no encontrado.");
            return "redirect:/admin/users";
        }

        if (newPassword == null || newPassword.trim().isEmpty()) {
            redirectAttributes.addFlashAttribute("errorMessage", "La nueva contraseña no puede estar vacía.");
            return "redirect:/admin/users";
        }

        user.setPassword(passwordEncoder.encode(newPassword.trim()));
        userRepository.save(user);
        redirectAttributes.addFlashAttribute("successMessage",
                "Contraseña de '" + user.getUsername() + "' actualizada correctamente.");
        return "redirect:/admin/users";
    }

    @PostMapping("/users/{id}/delete")
    public String deleteUser(
            @PathVariable Long id,
            Principal principal,
            RedirectAttributes redirectAttributes) {

        AppUser user = userRepository.findById(id).orElse(null);
        if (user == null) {
            redirectAttributes.addFlashAttribute("errorMessage", "Usuario no encontrado.");
            return "redirect:/admin/users";
        }

        if (user.getUsername().equals(principal.getName())) {
            redirectAttributes.addFlashAttribute("errorMessage", "No puedes eliminar tu propio usuario.");
            return "redirect:/admin/users";
        }

        String deletedName = user.getUsername();
        userRepository.delete(user);
        redirectAttributes.addFlashAttribute("successMessage", "Usuario '" + deletedName + "' eliminado.");
        return "redirect:/admin/users";
    }
}
