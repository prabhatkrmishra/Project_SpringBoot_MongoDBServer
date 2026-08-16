package com.pkmprojects.mongodbserver.service;

import com.pkmprojects.mongodbserver.error.NameNotAllowedException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Unit tests for name/password validation rules.
 */
class MongoNameValidatorTest {

    private final MongoNameValidator validator = new MongoNameValidator();

    @Test
    void acceptsValidDatabaseNames() {
        assertDoesNotThrow(() -> validator.validateDatabaseName("myapp"));
        assertDoesNotThrow(() -> validator.validateDatabaseName("my_app-1"));
        assertDoesNotThrow(() -> validator.validateDatabaseName("A1_b-C"));
    }

    @Test
    void rejectsBlankDatabaseName() {
        assertThatThrownBy(() -> validator.validateDatabaseName("  "))
                .isInstanceOf(NameNotAllowedException.class);
    }

    @Test
    void rejectsDisallowedCharactersInDatabaseName() {
        assertThatThrownBy(() -> validator.validateDatabaseName("my app"))
                .isInstanceOf(NameNotAllowedException.class);
        assertThatThrownBy(() -> validator.validateDatabaseName("my/app"))
                .isInstanceOf(NameNotAllowedException.class);
        assertThatThrownBy(() -> validator.validateDatabaseName("my$app"))
                .isInstanceOf(NameNotAllowedException.class);
        assertThatThrownBy(() -> validator.validateDatabaseName("my.app"))
                .isInstanceOf(NameNotAllowedException.class);
    }

    @Test
    void rejectsTooLongDatabaseName() {
        assertThatThrownBy(() -> validator.validateDatabaseName("a".repeat(65)))
                .isInstanceOf(NameNotAllowedException.class);
    }

    @Test
    void rejectsSystemDatabasesCaseInsensitively() {
        assertThatThrownBy(() -> validator.validateDatabaseName("admin"))
                .isInstanceOf(NameNotAllowedException.class);
        assertThatThrownBy(() -> validator.validateDatabaseName("local"))
                .isInstanceOf(NameNotAllowedException.class);
        assertThatThrownBy(() -> validator.validateDatabaseName("config"))
                .isInstanceOf(NameNotAllowedException.class);
        assertThatThrownBy(() -> validator.validateDatabaseName("mongodb_admin"))
                .isInstanceOf(NameNotAllowedException.class);
        assertThatThrownBy(() -> validator.validateDatabaseName("MongoDB_ADMIN"))
                .isInstanceOf(NameNotAllowedException.class);
    }

    @Test
    void acceptsValidUserNames() {
        assertDoesNotThrow(() -> validator.validateUserName("app.user_1"));
        assertDoesNotThrow(() -> validator.validateUserName("app-user"));
    }

    @Test
    void rejectsInvalidUserNames() {
        assertThatThrownBy(() -> validator.validateUserName("user name"))
                .isInstanceOf(NameNotAllowedException.class);
        assertThatThrownBy(() -> validator.validateUserName("a".repeat(65)))
                .isInstanceOf(NameNotAllowedException.class);
    }

    @Test
    void acceptsValidCollectionNames() {
        assertDoesNotThrow(() -> validator.validateCollectionName("items"));
        assertDoesNotThrow(() -> validator.validateCollectionName("order_items-2026"));
    }

    @Test
    void rejectsInvalidCollectionNames() {
        assertThatThrownBy(() -> validator.validateCollectionName("items.data"))
                .isInstanceOf(NameNotAllowedException.class);
    }

    @Test
    void blankPasswordAllowedButLengthEnforced() {
        assertDoesNotThrow(() -> validator.validatePassword(""));
        assertDoesNotThrow(() -> validator.validatePassword("12345678"));
        assertThatThrownBy(() -> validator.validatePassword("1234567"))
                .isInstanceOf(NameNotAllowedException.class);
        assertThatThrownBy(() -> validator.validatePassword("x".repeat(129)))
                .isInstanceOf(NameNotAllowedException.class);
    }
}
