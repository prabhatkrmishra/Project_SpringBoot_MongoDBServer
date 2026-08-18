package com.pkmprojects.mongodbserver.repository;

import com.mongodb.client.MongoClient;
import org.bson.Document;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Data-access gateway for MongoDB server administration using the MongoDB Java driver:
 * database/collection listing, user management, and paginated document reads.
 *
 * <p>Contains no business rules - only driver calls. All operations are bounded
 * (pagination via skip/limit, no unbounded materialization).</p>
 */
@Repository
public class MongoDatabaseRepository {

    private final MongoClient mongoClient;

    public MongoDatabaseRepository(MongoClient mongoClient) {
        this.mongoClient = mongoClient;
    }

    /**
     * @return names of every database in the server, including system databases
     */
    public List<String> listDatabaseNames() {
        List<String> names = new ArrayList<>();
        mongoClient.listDatabaseNames().forEach(names::add);
        return names;
    }

    /**
     * @return map of database name to total disk size in bytes (from {@code sizeOnDisk} field
     *         of the {@code listDatabases} command, which includes data files, indexes, and padding)
     */
    public Map<String, Long> getDatabaseSizes() {
        Map<String, Long> sizes = new LinkedHashMap<>();
        mongoClient.listDatabases().forEach(doc -> {
            String name = doc.getString("name");
            if (name != null) {
                sizes.put(name, doc.get("sizeOnDisk", 0L));
            }
        });
        return sizes;
    }

    /**
     * @return {@code true} when a database with the given name exists on the server
     */
    public boolean databaseExists(String dbName) {
        return listDatabaseNames().contains(dbName);
    }

    /**
     * @return names of the collections in {@code dbName}
     */
    public List<String> listCollectionNames(String dbName) {
        List<String> names = new ArrayList<>();
        mongoClient.getDatabase(dbName).listCollectionNames().forEach(names::add);
        return names;
    }

    /**
     * @return {@code true} when {@code collectionName} exists inside {@code dbName}
     */
    public boolean collectionExists(String dbName, String collectionName) {
        return listCollectionNames(dbName).contains(collectionName);
    }

    /**
     * Creates a Mongo user with readWrite rights scoped to exactly {@code dbName}.
     * The user is created <em>in</em> {@code dbName}, so connection strings of the
     * form {@code mongodb://user:pass@host/dbName} authenticate with the correct
     * authSource by default.
     */
    public void createUser(String dbName, String userName, String password) {
        Document command = new Document("createUser", userName)
                .append("pwd", password)
                .append("roles", List.of(new Document("role", "readWrite").append("db", dbName)));
        mongoClient.getDatabase(dbName).runCommand(command);
    }

    /**
     * Rotates a Mongo user's password, preserving its existing roles.
     */
    public void updateUserPassword(String dbName, String userName, String newPassword) {
        Document command = new Document("updateUser", userName).append("pwd", newPassword);
        mongoClient.getDatabase(dbName).runCommand(command);
    }

    /**
     * Removes the named user from {@code dbName}. Tolerated as a no-op by the
     * caller when the user does not exist (see {@link ProvisioningService}).
     */
    public void dropUser(String dbName, String userName) {
        Document command = new Document("dropUser", userName);
        mongoClient.getDatabase(dbName).runCommand(command);
    }

    /**
     * Materializes a database in MongoDB (which creates databases lazily on first
     * write) by creating a small bootstrap collection.
     */
    public void createDatabase(String dbName) {
        mongoClient.getDatabase(dbName).createCollection("_bootstrap");
    }

    /**
     * Creates a collection inside {@code dbName}. Fails with a driver exception
     * if the collection already exists.
     */
    public void createCollection(String dbName, String collectionName) {
        mongoClient.getDatabase(dbName).createCollection(collectionName);
    }

    /**
     * Drops a collection inside {@code dbName}.
     */
    public void dropCollection(String dbName, String collectionName) {
        mongoClient.getDatabase(dbName).getCollection(collectionName).drop();
    }

    /**
     * Drops the whole database, including any users stored in it.
     */
    public void dropDatabase(String dbName) {
        mongoClient.getDatabase(dbName).drop();
    }

    /**
     * @return number of documents in {@code dbName}.{@code collectionName}
     */
    public long countDocuments(String dbName, String collectionName) {
        return mongoClient.getDatabase(dbName).getCollection(collectionName).countDocuments();
    }

    /**
     * Reads one page of documents using skip/limit (bounded materialization).
     *
     * @param dbName         database name
     * @param collectionName collection name
     * @param skip           number of documents to skip
     * @param limit          maximum number of documents to return
     * @return the raw BSON documents
     */
    public List<Document> findDocuments(String dbName, String collectionName, int skip, int limit) {
        List<Document> documents = new ArrayList<>(limit);
        mongoClient.getDatabase(dbName).getCollection(collectionName)
                .find()
                .skip(skip)
                .limit(limit)
                .forEach(documents::add);
        return documents;
    }
}
