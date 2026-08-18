package com.pkmprojects.mongodbserver.dto;

import java.util.List;

/**
 * View model for a MongoDB user within a database, shown on the user management page.
 */
public record DatabaseUser(
        String userName,
        List<String> roles,
        String authSource) {
}
