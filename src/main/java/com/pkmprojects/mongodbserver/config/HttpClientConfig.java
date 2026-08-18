package com.pkmprojects.mongodbserver.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Shared HTTP client for outbound calls (webhook delivery).
 */
@Configuration(proxyBeanMethods = false)
public class HttpClientConfig {

    @Bean
    HttpClient httpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }
}