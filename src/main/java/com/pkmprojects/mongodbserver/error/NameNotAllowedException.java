package com.pkmprojects.mongodbserver.error;

/**
 * Thrown when a user-supplied name/password violates naming or policy rules
 * (maps to HTTP 400).
 */
public class NameNotAllowedException extends RuntimeException {

    public NameNotAllowedException(String message) {
        super(message);
    }
}
