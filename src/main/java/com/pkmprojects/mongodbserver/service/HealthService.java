package com.pkmprojects.mongodbserver.service;

import com.mongodb.MongoException;
import com.pkmprojects.mongodbserver.dto.ServerHealth;
import com.pkmprojects.mongodbserver.repository.MongoDatabaseRepository;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Assembles MongoDB server health metrics for the health dashboard.
 *
 * <p>Reachability comes from {@code ping}, which any authenticated user can
 * run. Version/uptime/connections require {@code serverStatus} (admin or
 * {@code clusterMonitor} privileges) and degrade to {@code null} when the
 * connected user lacks them. Database count/storage come from the same
 * {@code listDatabases} calls the rest of the app uses.
 */
@Service
public class HealthService {

    private static final Logger log = LoggerFactory.getLogger(HealthService.class);

    private final MongoDatabaseRepository mongoDatabaseRepository;

    public HealthService(MongoDatabaseRepository mongoDatabaseRepository) {
        this.mongoDatabaseRepository = mongoDatabaseRepository;
    }

    public ServerHealth getHealth() {
        boolean reachable = ping();

        String version = null;
        Long uptimeSeconds = null;
        Integer connectionCount = null;
        if (reachable) {
            try {
                Document status = mongoDatabaseRepository.getServerStatus();
                version = status.getString("version");
                uptimeSeconds = toLong(status.get("uptime"));
                Document connections = status.get("connections", Document.class);
                if (connections != null) {
                    Object current = connections.get("current");
                    connectionCount = current instanceof Number number ? number.intValue() : 0;
                }
            } catch (MongoException e) {
                log.warn("serverStatus unavailable for connected MongoDB (insufficient privileges?)", e);
            }
        }

        int databaseCount = 0;
        Long totalStorageBytes = null;
        if (reachable) {
            try {
                Map<String, Long> sizes = mongoDatabaseRepository.getDatabaseSizes();
                databaseCount = sizes.size();
                totalStorageBytes = sizes.values().stream().mapToLong(Long::longValue).sum();
            } catch (MongoException e) {
                log.warn("Could not read database sizes for health dashboard", e);
            }
        }

        return new ServerHealth(reachable, version, uptimeSeconds, databaseCount, totalStorageBytes, connectionCount);
    }

    private boolean ping() {
        try {
            mongoDatabaseRepository.ping();
            return true;
        } catch (MongoException e) {
            log.warn("MongoDB ping failed", e);
            return false;
        }
    }

    private static Long toLong(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }
}
