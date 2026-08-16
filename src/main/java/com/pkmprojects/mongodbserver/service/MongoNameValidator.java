package com.pkmprojects.mongodbserver.service;

import com.pkmprojects.mongodbserver.error.NameNotAllowedException;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;

/**
 * Validates MongoDB database/collection/user names. Names also travel in URL
 * paths, so the allowed charset is deliberately restricted to URL-safe ASCII.
 */
@Component
public class MongoNameValidator {

    /**
     * Databases managed by MongoDB itself or by this application; never user-manageable.
     */
    static final Set<String> SYSTEM_DATABASES = Set.of("admin", "local", "config", "mongodb_admin");

    private static final int MAX_NAME_LENGTH = 64;

    private static final String DATABASE_NAME_PATTERN = "[A-Za-z0-9_-]+";
    private static final String USER_NAME_PATTERN = "[A-Za-z0-9_.-]+";
    private static final String COLLECTION_NAME_PATTERN = "[A-Za-z0-9_-]+";

    /**
     * Validates a database name: URL-safe charset, length limit, and not a
     * system database.
     *
     * @throws NameNotAllowedException when the name violates the rules
     */
    public void validateDatabaseName(String dbName) {
        requireValid(dbName, DATABASE_NAME_PATTERN, "Database name may only contain letters, digits, '_' and '-'");
        String lower = dbName.toLowerCase(Locale.ROOT);
        if (SYSTEM_DATABASES.contains(lower)) {
            throw new NameNotAllowedException("Database '" + dbName + "' is a system database and cannot be managed");
        }
    }

    /**
     * Validates a collection name (URL-safe charset, length limit).
     *
     * @throws NameNotAllowedException when the name violates the rules
     */
    public void validateCollectionName(String collectionName) {
        requireValid(collectionName, COLLECTION_NAME_PATTERN, "Collection name may only contain letters, digits, '_' and '-'");
    }

    /**
     * Validates a Mongo user name (URL-safe charset, length limit).
     *
     * @throws NameNotAllowedException when the name violates the rules
     */
    public void validateUserName(String userName) {
        requireValid(userName, USER_NAME_PATTERN, "Database user name may only contain letters, digits, '.', '_' and '-'");
    }

    /**
     * Validates an explicit password. Blank passwords are accepted here - they
     * mean "generate one" in the provisioning service.
     *
     * @throws NameNotAllowedException when the password is too short or too long
     */
    public void validatePassword(String password) {
        if (password == null || password.isBlank()) {
            return; // blank means "generate one"
        }
        if (password.length() < 8) {
            throw new NameNotAllowedException("Password must be at least 8 characters");
        }
        if (password.length() > 128) {
            throw new NameNotAllowedException("Password must be at most 128 characters");
        }
    }

    private void requireValid(String value, String pattern, String message) {
        if (value == null || value.isBlank()) {
            throw new NameNotAllowedException("A name is required");
        }
        if (value.length() > MAX_NAME_LENGTH) {
            throw new NameNotAllowedException("Name must be at most " + MAX_NAME_LENGTH + " characters");
        }
        if (!value.matches(pattern)) {
            throw new NameNotAllowedException(message);
        }
    }
}
