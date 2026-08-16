package com.pkmprojects.mongodbserver.dto;

import jakarta.validation.constraints.Size;

/**
 * Form backing object for resetting a provisioned database user's password.
 * Blank {@code password} means "generate one".
 */
public record ResetPasswordForm(

        @Size(max = 128, message = "Password must be at most 128 characters")
        String password) {
}
