package com.example.app;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Postgres with the production split of roles: Flyway migrates as the container's superuser (the owner), the
 * application connects as {@code app_runtime}, which row-level security applies to.
 */
@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

	@Bean
	PostgreSQLContainer postgresContainer() {
		return new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
				.withInitScript("app-runtime-role.sql");
	}

	@Bean
	DynamicPropertyRegistrar postgresProperties(PostgreSQLContainer postgres) {
		return registry -> {
			registry.add("spring.datasource.url", postgres::getJdbcUrl);
			registry.add("spring.datasource.username", () -> "app_runtime");
			registry.add("spring.datasource.password", () -> "app_runtime");
			registry.add("spring.flyway.url", postgres::getJdbcUrl);
			registry.add("spring.flyway.user", postgres::getUsername);
			registry.add("spring.flyway.password", postgres::getPassword);
		};
	}

}
