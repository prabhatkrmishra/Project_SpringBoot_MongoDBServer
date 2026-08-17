package com.pkmprojects.mongodbserver.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

/**
 * Metadata about a provisioned database: which Mongo user owns it, when it was
 * created, and when its password was last reset. Stored in the {@code mongodb_admin}
 * database. The stored password allows the connection string to be reconstructed
 * and shown on the database detail page at any time.
 */
@Document(collection = "provisioned_databases")
public class ManagedDatabase {

    @Id
    private String id;

    private String dbName;

    private String userName;

    private List<String> roles;

    private Instant createdAt;

    private Instant updatedAt;

    private Instant lastPasswordResetAt;

    private String storedPassword;

    public ManagedDatabase() {
        // for Spring Data
    }

    /**
     * Creates provisioned metadata for a database.
     *
     * @param dbName              database name (also used as the document id)
     * @param userName            dedicated Mongo user name
     * @param roles               readWrite roles granted to the user
     * @param createdAt           provisioning time
     * @param updatedAt           last update time
     * @param lastPasswordResetAt last password rotation, or {@code null} if never
     */
    public ManagedDatabase(String dbName, String userName, List<String> roles,
                           Instant createdAt, Instant updatedAt, Instant lastPasswordResetAt) {
        this.id = dbName;
        this.dbName = dbName;
        this.userName = userName;
        this.roles = List.copyOf(roles);
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.lastPasswordResetAt = lastPasswordResetAt;
    }

    public String getId() {
        return id;
    }

    public String getDbName() {
        return dbName;
    }

    public String getUserName() {
        return userName;
    }

    public List<String> getRoles() {
        return roles;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getLastPasswordResetAt() {
        return lastPasswordResetAt;
    }

    public String getStoredPassword() {
        return storedPassword;
    }

    public void setStoredPassword(String storedPassword) {
        this.storedPassword = storedPassword;
    }

    /**
     * Records a password rotation; also bumps {@code updatedAt} to the same
     * instant so the metadata reflects the last change.
     */
    public void setLastPasswordResetAt(Instant lastPasswordResetAt) {
        this.lastPasswordResetAt = lastPasswordResetAt;
        this.updatedAt = lastPasswordResetAt;
    }
}
