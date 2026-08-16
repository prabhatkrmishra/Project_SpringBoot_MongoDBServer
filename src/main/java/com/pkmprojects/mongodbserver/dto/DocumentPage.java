package com.pkmprojects.mongodbserver.dto;

import java.util.List;

/**
 * View model for one page of documents in the explorer.
 * {@code documents} contains extended-JSON strings rendered as escaped text.
 */
public record DocumentPage(
        String dbName,
        String collectionName,
        int page,
        int pageSize,
        long totalCount,
        int totalPages,
        List<String> documents,
        boolean hasPrev,
        boolean hasNext) {

    /**
     * @return a page describing an empty result set (e.g. an empty collection)
     */
    public static DocumentPage empty(String dbName, String collectionName, int page, int pageSize) {
        return new DocumentPage(dbName, collectionName, page, pageSize, 0, 0, List.of(), false, false);
    }
}
