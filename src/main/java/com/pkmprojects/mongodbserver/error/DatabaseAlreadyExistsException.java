package com.pkmprojects.mongodbserver.error;

/**
 * Thrown when a database (or its user) already exists (maps to HTTP 409).
 */
public class DatabaseAlreadyExistsException extends RuntimeException {

    public DatabaseAlreadyExistsException(String message) {
        super(message);
    }
}
