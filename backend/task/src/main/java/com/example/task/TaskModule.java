package com.example.task;

import java.util.Arrays;
import java.util.stream.Stream;

import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.flywaydb.core.api.Location;

import com.example.common.CommonModule;
import com.example.security.SecurityModule;
import com.example.tenancy.TenancyModule;

/**
 * Entry point of the task bounded context. A deployable app includes the context with {@code @Import(TaskModule.class)}.
 *
 * <p>The module owns its code, its tables (Postgres schema {@code task}) and its migrations
 * ({@code db/migration/task}); nothing outside the module touches them. Tasks belong to a tenant (see
 * {@code TenancyModule}).
 */
@Configuration(proxyBeanMethods = false)
@Import({ CommonModule.class, SecurityModule.class, TenancyModule.class })
@ComponentScan(basePackageClasses = TaskModule.class)
@EntityScan(basePackageClasses = TaskModule.class)
@EnableJpaRepositories(basePackageClasses = TaskModule.class)
public class TaskModule {

	@Bean
	FlywayConfigurationCustomizer taskMigrations() {
		// Boot's default db/migration scans recursively, so Flyway would discard this folder and run every module's.
		return configuration -> configuration.locations(Stream.concat(
				Arrays.stream(configuration.getLocations()).filter(l -> !l.getDescriptor().equals("classpath:db/migration")),
				Stream.of(new Location("classpath:db/migration/task"))).toArray(Location[]::new));
	}

}
