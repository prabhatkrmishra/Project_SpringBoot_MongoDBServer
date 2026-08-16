package com.pkmprojects.mongodbserver.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Unit tests for the generated-password alphabet, length, and uniqueness.
 */
class PasswordGeneratorTest {

    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#$%";

    private final PasswordGenerator generator = new PasswordGenerator();

    @Test
    void generatesRequestedLength() {
        assertThat(generator.generate(16)).hasSize(16);
        assertThat(generator.generate(8)).hasSize(8);
    }

    @Test
    void usesOnlyUnambiguousAlphabet() {
        String password = generator.generate(64);
        for (char c : password.toCharArray()) {
            assertThat(ALPHABET).contains(String.valueOf(c));
        }
    }

    @Test
    void generatesDifferentPasswords() {
        assertNotEquals(generator.generate(16), generator.generate(16));
    }

    @Test
    void rejectsLengthBelowEight() {
        assertThatThrownBy(() -> generator.generate(7))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
