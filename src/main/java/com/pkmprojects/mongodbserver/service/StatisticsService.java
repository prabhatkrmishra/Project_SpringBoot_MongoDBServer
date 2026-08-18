package com.pkmprojects.mongodbserver.service;

import com.mongodb.MongoCommandException;
import com.pkmprojects.mongodbserver.dto.CollectionStats;
import com.pkmprojects.mongodbserver.dto.DatabaseStats;
import com.pkmprojects.mongodbserver.error.DatabaseNotFoundException;
import com.pkmprojects.mongodbserver.error.ProvisioningException;
import com.pkmprojects.mongodbserver.repository.MongoDatabaseRepository;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Read-only database statistics: aggregate {@code dbStats} plus per-collection
 * {@code collStats}. No business rules - the validator guards names and each
 * collection is read with a single bounded command.
 */
@Service
public class StatisticsService {

    private static final Logger log = LoggerFactory.getLogger(StatisticsService.class);

    private final MongoDatabaseRepository mongoDatabaseRepository;
    private final MongoNameValidator nameValidator;

    public StatisticsService(MongoDatabaseRepository mongoDatabaseRepository, MongoNameValidator nameValidator) {
        this.mongoDatabaseRepository = mongoDatabaseRepository;
        this.nameValidator = nameValidator;
    }

    /**
     * @return aggregate and per-collection statistics for {@code dbName}
     * @throws DatabaseNotFoundException when the database does not exist
     * @throws ProvisioningException     when {@code dbStats}/{@code collStats} cannot be read
     */
    public DatabaseStats getDatabaseStats(String dbName) {
        nameValidator.validateDatabaseName(dbName);
        requireDatabase(dbName);

        Document stats;
        try {
            stats = mongoDatabaseRepository.getDbStats(dbName);
        } catch (MongoCommandException e) {
            log.warn("Could not read dbStats for {}", dbName, e);
            throw new ProvisioningException("Could not read statistics for database '" + dbName + "'", e);
        }

        List<CollectionStats> collections = mongoDatabaseRepository.listCollectionNames(dbName).stream()
                .map(collection -> collectionStats(dbName, collection))
                .toList();

        return new DatabaseStats(
                dbName,
                intValue(stats.get("collections")),
                intValue(stats.get("views")),
                longValue(stats.get("objects")),
                longValue(stats.get("dataSize")),
                longValue(stats.get("storageSize")),
                longValue(stats.get("avgObjSize")),
                intValue(stats.get("indexes")),
                longValue(stats.get("indexSize")),
                collections);
    }

    private CollectionStats collectionStats(String dbName, String collectionName) {
        Document stats;
        try {
            stats = mongoDatabaseRepository.getCollectionStats(dbName, collectionName);
        } catch (MongoCommandException e) {
            log.warn("Could not read collStats for {}.{}", dbName, collectionName, e);
            throw new ProvisioningException("Could not read statistics for collection '" + collectionName + "'", e);
        }
        return new CollectionStats(
                collectionName,
                longValue(stats.get("count")),
                longValue(stats.get("size")),
                longValue(stats.get("storageSize")),
                longValue(stats.get("avgObjSize")),
                intValue(stats.get("nindexes")),
                longValue(stats.get("totalIndexSize")));
    }

    private void requireDatabase(String dbName) {
        if (!mongoDatabaseRepository.databaseExists(dbName)) {
            throw new DatabaseNotFoundException("Database '" + dbName + "' does not exist");
        }
    }

    private static long longValue(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static int intValue(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }
}