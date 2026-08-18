package com.pkmprojects.mongodbserver.dto;

/**
 * View model for one collection's statistics, from the {@code collStats} command.
 */
public record CollectionStats(
        String name,
        long documentCount,
        long dataSizeBytes,
        long storageSizeBytes,
        long averageObjectSizeBytes,
        int indexCount,
        long indexSizeBytes) {

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