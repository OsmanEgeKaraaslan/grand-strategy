package com.reach.strategy;

import org.springframework.boot.SpringApplication;

public class TestStrategyBackendApplication {

	public static void main(String[] args) {
		SpringApplication.from(StrategyBackendApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
