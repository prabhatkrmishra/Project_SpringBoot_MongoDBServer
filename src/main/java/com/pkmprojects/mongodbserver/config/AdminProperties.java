package com.pkmprojects.mongodbserver.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Web app administrator account, bound from {@code app.admin.*} (values come from
 * {@code APP_ADMIN_USERNAME} / {@code APP_ADMIN_PASSWORD} in {@code .env}).
 *
 * @param username admin login name
 * @param password admin login password (plaintext source of truth; BCrypt-encoded in memory)
 */
@ConfigurationProperties(prefix = "app.admin")
public record AdminProperties(String username, String password) {
}
