package com.pkmprojects.mongodbserver.dto;

/**
 * View model for one collection inside a database.
 */
public record CollectionInfo(String name, long documentCount) {
}
