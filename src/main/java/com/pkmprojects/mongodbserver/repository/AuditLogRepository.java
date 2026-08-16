package com.pkmprojects.mongodbserver.repository;

import com.pkmprojects.mongodbserver.model.AuditEvent;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

/**
 * Read access to the admin activity audit trail.
 */
public interface AuditLogRepository extends MongoRepository<AuditEvent, String> {

    /**
     * @return the 10 most recent audit events, newest first
     */
    List<AuditEvent> findTop10ByOrderByPerformedAtDesc();
}
