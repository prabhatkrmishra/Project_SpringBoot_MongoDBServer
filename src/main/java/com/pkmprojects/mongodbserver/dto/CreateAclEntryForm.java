package com.pkmprojects.mongodbserver.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Form data for creating/updating a RESTHeart ACL entry.
 * Uses RESTHeart's predicate-based format.
 */
public record CreateAclEntryForm(
        @NotBlank(message = "Rule ID is required")
        @Size(max = 64, message = "Rule ID must be at most 64 characters")
        @Pattern(regexp = "[A-Za-z0-9_.\\-]+", message = "Rule ID may only contain letters, digits, '.', '_' and '-'")
        String ruleId,

        @NotBlank(message = "Predicate is required")
        @Size(max = 512, message = "Predicate must be at most 512 characters")
        String predicate,

        @Size(max = 256, message = "Roles must be at most 256 characters")
        String roles,

        int priority
) {
    /**
     * Returns the roles field split by comma, trimmed, and non-empty entries only.
     */
    public List<String> parsedRoles() {
        if (roles == null || roles.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(roles.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
