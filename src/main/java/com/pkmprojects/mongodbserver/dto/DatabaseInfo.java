package com.pkmprojects.mongodbserver.dto;

import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;
import java.util.List;

/**
 * View model for a database shown on the dashboard / detail page.
 * Never exposes a password; {@code connectionString} is only populated for the
 * "show once" flash message after creation or password reset.
 *
 * <p>Serializable because the show-once message travels as a flash attribute,
 * and flash attributes are stored in the Redis-backed HTTP session (JDK
 * serialization).
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
        String connectionString) implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * @return a copy of this view model with the show-once connection string set
     */
    public DatabaseInfo withConnectionString(String connectionString) {
        return new DatabaseInfo(dbName, userName, roles, collectionsCount, createdAt, updatedAt,
                lastPasswordResetAt, provisioned, connectionString);
    }
}
