package com.sanvi.jobscheduler.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Base class for integration tests. All subclasses share one cached application context
 * (API profile, Testcontainers Postgres + Redis, background scheduling disabled).
 */
@SpringBootTest
@ActiveProfiles({"api", "test"})
@Import(TestcontainersConfiguration.class)
public abstract class AbstractIntegrationTest {
}
