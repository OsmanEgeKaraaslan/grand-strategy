package com.reach.strategy;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.reach.strategy.support.TestJwtIssuer;

/**
 * Full application context against a real PostgreSQL started by Testcontainers, verifying
 * access tokens from {@link TestJwtIssuer} with the production JWT decoder.
 * <p>
 * Classes are skipped (not failed) when no Docker daemon is available so the unit suite still
 * runs on machines without Docker; CI checks for Docker before running, so the skip cannot hide
 * failures there.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@ContextConfiguration(initializers = TestJwtIssuer.PropertiesInitializer.class)
@Testcontainers(disabledWithoutDocker = true)
public @interface PostgresIntegrationTest {

}
