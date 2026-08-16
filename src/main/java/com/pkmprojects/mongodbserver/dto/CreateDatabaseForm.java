package com.pkmprojects.mongodbserver.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Form backing object for provisioning a new database with a dedicated user.
 * Blank {@code password} means "generate one" (handled in the service).
 */
public record CreateDatabaseForm(

        @NotBlank(message = "Database name is required")
        @Size(max = 64, message = "Database name must be at most 64 characters")
        @Pattern(regexp = "[A-Za-z0-9_-]+", message = "Database name may only contain letters, digits, '_' and '-'")
        String dbName,

        @NotBlank(message = "Database user name is required")
        @Size(max = 64, message = "Database user name must be at most 64 characters")
        @Pattern(regexp = "[A-Za-z0-9_.-]+", message = "Database user name may only contain letters, digits, '.', '_' and '-'")
        String userName,

        @Size(max = 128, message = "Password must be at most 128 characters")
        String password) {
}
