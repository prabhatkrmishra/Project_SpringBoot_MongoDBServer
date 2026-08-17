package com.pkmprojects.mongodbserver.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Form data for creating a RESTHeart user.
 */
public record CreateRestheartUserForm(
        @NotBlank(message = "Username is required")
        @Size(max = 64, message = "Username must be at most 64 characters")
        @Pattern(regexp = "[A-Za-z0-9_.\\-]+", message = "Username may only contain letters, digits, '.', '_' and '-'")
        String userName,

        @NotBlank(message = "Password is required")
        @Size(min = 8, max = 128, message = "Password must be between 8 and 128 characters")
        String password,

        @Size(max = 256, message = "Roles must be at most 256 characters")
        String roles
) {
    /**
     * Returns the roles field split by comma, trimmed, and non-empty entries only.
     */
    public java.util.List<String> parsedRoles() {
        if (roles == null || roles.isBlank()) {
            return java.util.List.of();
        }
        return java.util.Arrays.stream(roles.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
