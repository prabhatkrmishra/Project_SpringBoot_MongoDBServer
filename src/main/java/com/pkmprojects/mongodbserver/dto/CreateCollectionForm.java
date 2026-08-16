package com.pkmprojects.mongodbserver.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Form backing object for creating a collection inside a provisioned database.
 */
public record CreateCollectionForm(

        @NotBlank(message = "Collection name is required")
        @Size(max = 64, message = "Collection name must be at most 64 characters")
        @Pattern(regexp = "[A-Za-z0-9_-]+", message = "Collection name may only contain letters, digits, '_' and '-'")
        String collectionName) {
}
