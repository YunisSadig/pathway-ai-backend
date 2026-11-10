package com.pathwayai.backend;

import org.springframework.boot.SpringApplication;

public class TestPathwayAiBackendApplication {

	public static void main(String[] args) {
		SpringApplication.from(PathwayAiBackendApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
