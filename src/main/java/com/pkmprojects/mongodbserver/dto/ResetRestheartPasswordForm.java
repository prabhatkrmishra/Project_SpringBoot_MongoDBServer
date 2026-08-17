package com.pkmprojects.mongodbserver.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Form for resetting a RESTHeart user's password.
 */
public record ResetRestheartPasswordForm(
        @NotBlank(message = "Password is required")
        @Size(min = 8, max = 128, message = "Password must be between 8 and 128 characters")
        String password
) {}
