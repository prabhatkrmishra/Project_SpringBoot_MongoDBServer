package com.pkmprojects.mongodbserver.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Form data for creating/updating a RESTHeart ACL entry.
 */
public record CreateAclEntryForm(
        @NotBlank(message = "Rule ID is required")
        @Size(max = 64, message = "Rule ID must be at most 64 characters")
        @Pattern(regexp = "[A-Za-z0-9_.\\-]+", message = "Rule ID may only contain letters, digits, '.', '_' and '-'")
        String ruleId,

        @NotBlank(message = "URL pattern is required")
        @Size(max = 256, message = "URL pattern must be at most 256 characters")
        String url,

        @Size(max = 256, message = "Methods must be at most 256 characters")
        String methods,

        @Size(max = 256, message = "Roles must be at most 256 characters")
        String roles,

        boolean authenticationRequired
) {
    /**
     * Returns the methods field split by comma, trimmed, uppercased, and non-empty entries only.
     */
    public List<String> parsedMethods() {
        if (methods == null || methods.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(methods.split(","))
                .map(String::trim)
                .map(String::toUpperCase)
                .filter(s -> !s.isEmpty())
                .toList();
    }

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
