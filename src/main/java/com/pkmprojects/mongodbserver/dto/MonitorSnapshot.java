package com.pkmprojects.mongodbserver.dto;

import java.time.Instant;

/**
 * One live snapshot of MongoDB server activity, pushed to the monitor page
 * over SSE every couple of seconds. Fields derived from {@code serverStatus}
 * are {@code null} when the connected user lacks the required privileges; the
 * page renders those as "—". Op/network rates are per-second deltas since the
 * previous snapshot (zero on the first snapshot).
 */
public record MonitorSnapshot(
        boolean reachable,
        Instant measuredAt,
        String version,
        Long uptimeSeconds,
        Integer connectionCount,
        int databaseCount,
        Long totalStorageBytes,
        OpsRate ops,
        NetworkRate network,
        MemUsage mem,
        LockQueue lock) {

    /**
     * Per-second operation rates since the previous snapshot.
     */
    public record OpsRate(long insert, long query, long update, long delete, long command) {
    }

    /**
     * Per-second network byte rates since the previous snapshot.
     */
    public record NetworkRate(long bytesInPerSecond, long bytesOutPerSecond) {
    }

    /**
     * MongoDB process memory (resident/virtual) in megabytes.
     */
    public record MemUsage(Long residentMb, Long virtualMb) {
    }

    /**
     * Global lock wait queues and active clients.
     */
    public record LockQueue(long queueTotal, long queueReaders, long queueWriters,
                            long activeClientsTotal, long activeClientsReaders, long activeClientsWriters) {
    }
}