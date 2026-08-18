package com.pkmprojects.mongodbserver.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * One admin action on the provisioning lifecycle, stored in the
 * {@code admin_activity} collection for auditability. Passwords are never part
 * of these records.
 */
@Document(collection = "admin_activity")
public class AuditEvent {

    /**
     * Event type: a database was provisioned.
     */
    public static final String PROVISION = "PROVISION";
    /**
     * Event type: a provisioned user's password was rotated.
     */
    public static final String RESET_PASSWORD = "RESET_PASSWORD";
    /**
     * Event type: a database was deleted.
     */
    public static final String DELETE = "DELETE";
    /**
     * Event type: a database user was revoked.
     */
    public static final String REVOKE_USER = "REVOKE_USER";

    @Id
    private String id;

    private String eventType;

    private String dbName;

    private String userName;

    private String performedBy;

    private Instant performedAt;

    public AuditEvent() {
        // for Spring Data
    }

    /**
     * Records one admin action on the provisioning lifecycle.
     *
     * @param eventType   one of {@link #PROVISION}, {@link #RESET_PASSWORD}, {@link #DELETE}, {@link #REVOKE_USER}
     * @param dbName      affected database
     * @param userName    affected database user, or {@code null} (e.g. delete of a
     *                    database that was never provisioned)
     * @param performedBy admin username from the security context
     * @param performedAt action timestamp
     */
    public AuditEvent(String eventType, String dbName, String userName, String performedBy, Instant performedAt) {
        this.eventType = eventType;
        this.dbName = dbName;
        this.userName = userName;
        this.performedBy = performedBy;
        this.performedAt = performedAt;
    }

    public String getId() {
        return id;
    }

    public String getEventType() {
        return eventType;
    }

    public String getDbName() {
        return dbName;
    }

    public String getUserName() {
        return userName;
    }

    public String getPerformedBy() {
        return performedBy;
    }

    public Instant getPerformedAt() {
        return performedAt;
    }
}
