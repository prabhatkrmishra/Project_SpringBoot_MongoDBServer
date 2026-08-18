package com.pkmprojects.mongodbserver.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Form backing object for creating a webhook endpoint. An empty
 * {@code eventTypes} list means "all events".
 */
public record WebhookForm(

        @NotBlank(message = "Name is required")
        @Size(max = 64, message = "Name must be at most 64 characters")
        String name,

        @NotBlank(message = "URL is required")
        @Size(max = 500, message = "URL must be at most 500 characters")
        String url,

        @Size(max = 128, message = "Secret must be at most 128 characters")
        String secret,

        List<String> eventTypes) {
}