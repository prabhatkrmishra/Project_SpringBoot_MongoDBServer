package com.pkmprojects.mongodbserver.error;

/**
 * Thrown when a requested database or collection does not exist (maps to HTTP 404).
 */
public class DatabaseNotFoundException extends RuntimeException {

    public DatabaseNotFoundException(String message) {
        super(message);
    }
}
