package com.pkmprojects.mongodbserver.dto;

import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;
import java.util.List;

/**
 * View model for a database shown on the dashboard / detail page.
 * Contains the RESTHeart API password (stored in provisioning metadata)
 * so admins can always recover their credentials.
 *
 * <p>Serializable because the show-once message travels as a flash attribute,
 * and flash attributes are stored in the HTTP session (JDK serialization).</p>
 */
public record DatabaseInfo(
        String dbName,
        String userName,
        List<String> roles,
        long collectionsCount,
        Instant createdAt,
        Instant updatedAt,
        Instant lastPasswordResetAt,
        boolean provisioned,
        String restheartEnvVars,
        String restheartUrl,
        String restheartPassword) implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * @return a copy of this view model with the show-once RESTHeart env vars set
     */
    public DatabaseInfo withRestheartEnvVars(String envVars, String url) {
        return new DatabaseInfo(dbName, userName, roles, collectionsCount, createdAt, updatedAt,
                lastPasswordResetAt, provisioned, envVars, url, restheartPassword);
    }
}
