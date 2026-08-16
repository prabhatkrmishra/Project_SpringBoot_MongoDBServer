package com.pkmprojects.mongodbserver.security;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Objects;

/**
 * Generates strong random passwords for provisioned database users.
 * Uses {@link SecureRandom} (never {@code Math.random()}).
 */
@Component
public class PasswordGenerator {

    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#$%";

    private final SecureRandom random;

    public PasswordGenerator() {
        this(new SecureRandom());
    }

    PasswordGenerator(SecureRandom random) {
        this.random = Objects.requireNonNull(random, "random");
    }

    /**
     * @param length desired password length (at least 8)
     * @return random password using an unambiguous alphabet (no 0/O, 1/l)
     */
    public String generate(int length) {
        if (length < 8) {
            throw new IllegalArgumentException("password length must be at least 8");
        }
        StringBuilder password = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            password.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return password.toString();
    }
}
