package com.pkmprojects.mongodbserver.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Shared infrastructure beans.
 */
@Configuration(proxyBeanMethods = false)
public class TimeConfig {

    /**
     * The application clock. Injected instead of calling {@code Instant.now()}
     * so business logic stays deterministic and testable.
     */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
