package com.pkmprojects.mongodbserver;

import org.springframework.boot.SpringApplication;

/**
 * Development entry point that boots the real application on Testcontainers
 * instead of a local/remote MongoDB.
 */
public class TestMongodbserverApplication {

    public static void main(String[] args) {
        SpringApplication.from(MongodbserverApplication::main).with(TestcontainersConfiguration.class).run(args);
    }

}
