package com.reach.strategy;

import java.time.Clock;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class StrategyBackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(StrategyBackendApplication.class, args);
	}

	// Single time source so tests can fix the clock; game rules never read it (docs/TURN_SYSTEM.md §6).
	@Bean
	Clock clock() {
		return Clock.systemUTC();
	}

}
