package com.pkmprojects.mongodbserver.error;

/**
 * Thrown when a webhook endpoint referenced by id does not exist.
 */
public class WebhookNotFoundException extends RuntimeException {

    public WebhookNotFoundException(String message) {
        super(message);
    }
}