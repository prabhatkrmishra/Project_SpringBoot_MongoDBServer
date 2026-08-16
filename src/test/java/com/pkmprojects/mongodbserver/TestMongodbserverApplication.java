package com.pkmprojects.mongodbserver;

import org.springframework.boot.SpringApplication;

public class TestMongodbserverApplication {

	public static void main(String[] args) {
		SpringApplication.from(MongodbserverApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
