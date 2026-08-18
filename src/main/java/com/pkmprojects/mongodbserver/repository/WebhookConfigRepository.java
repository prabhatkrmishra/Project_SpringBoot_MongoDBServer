package com.pkmprojects.mongodbserver.repository;

import com.pkmprojects.mongodbserver.model.WebhookConfig;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

/**
 * Spring Data repository for webhook endpoint configuration (stored in the
 * {@code mongodb_admin} database). Business rules live in the service layer.
 */
public interface WebhookConfigRepository extends MongoRepository<WebhookConfig, String> {

    /**
     * @return every enabled webhook, for delivery fan-out
     */
    List<WebhookConfig> findByEnabledTrue();
}