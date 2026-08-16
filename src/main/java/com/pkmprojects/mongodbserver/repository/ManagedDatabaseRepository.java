package com.pkmprojects.mongodbserver.repository;

import com.pkmprojects.mongodbserver.model.ManagedDatabase;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

/**
 * Spring Data repository for provisioning metadata (stored in the {@code mongodb_admin}
 * database). Business rules live in the service layer, not here.
 */
public interface ManagedDatabaseRepository extends MongoRepository<ManagedDatabase, String> {

    /**
     * @return the provisioning metadata for {@code dbName}, if provisioned
     */
    Optional<ManagedDatabase> findByDbName(String dbName);

    /**
     * @return {@code true} when {@code dbName} has provisioning metadata
     */
    boolean existsByDbName(String dbName);

    /**
     * Removes the provisioning metadata for {@code dbName} (no-op when absent).
     */
    void deleteByDbName(String dbName);
}
