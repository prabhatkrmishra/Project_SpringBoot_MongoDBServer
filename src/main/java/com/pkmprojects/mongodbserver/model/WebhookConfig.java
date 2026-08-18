package com.pkmprojects.mongodbserver.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

/**
 * A configured webhook endpoint that receives HTTP notifications when admin
 * actions occur. Stored in the {@code mongodb_admin} database. An empty
 * {@code eventTypes} list means "all events". The secret (when set) is used to
 * sign the payload with HMAC-SHA256 so receivers can verify authenticity.
 */
@Document(collection = "webhook_configs")
public class WebhookConfig {

    @Id
    private String id;

    private String name;

    private String url;

    private String secret;

    private boolean enabled;

    private List<String> eventTypes;

    private Instant createdAt;

    public WebhookConfig() {
        // for Spring Data
    }

    public WebhookConfig(String name, String url, String secret, List<String> eventTypes,
                         boolean enabled, Instant createdAt) {
        this.name = name;
        this.url = url;
        this.secret = secret;
        this.eventTypes = List.copyOf(eventTypes);
        this.enabled = enabled;
        this.createdAt = createdAt;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getUrl() {
        return url;
    }

    public String getSecret() {
        return secret;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<String> getEventTypes() {
        return eventTypes;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}