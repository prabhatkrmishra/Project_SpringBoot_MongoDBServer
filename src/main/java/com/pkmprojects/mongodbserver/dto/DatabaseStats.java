package com.pkmprojects.mongodbserver.dto;

import java.util.List;

/**
 * View model for the database statistics dashboard, from the {@code dbStats}
 * command plus per-collection {@code collStats} data.
 */
public record DatabaseStats(
        String dbName,
        int collectionCount,
        int viewCount,
        long totalDocuments,
        long dataSizeBytes,
        long storageSizeBytes,
        long averageObjectSizeBytes,
        int indexCount,
        long indexSizeBytes,
        List<CollectionStats> collections) {

    public String dataSizeLabel() {
        return ByteSize.format(dataSizeBytes);
    }

    public String storageSizeLabel() {
        return ByteSize.format(storageSizeBytes);
    }

    public String averageObjectSizeLabel() {
        return ByteSize.format(averageObjectSizeBytes);
    }

    public String indexSizeLabel() {
        return ByteSize.format(indexSizeBytes);
    }
}