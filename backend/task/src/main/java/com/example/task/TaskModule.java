package com.example.task;

import java.util.Arrays;

import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.flywaydb.core.api.Location;

import com.example.common.CommonModule;

/**
 * Entry point of the task bounded context. A deployable app includes the context with {@code @Import(TaskModule.class)}.
 *
 * <p>The module owns its code, its tables (Postgres schema {@code task}) and its migrations
 * ({@code db/migration/task}); nothing outside the module touches them.
 */
@Configuration(proxyBeanMethods = false)
@Import(CommonModule.class)
@ComponentScan(basePackageClasses = TaskModule.class)
@EntityScan(basePackageClasses = TaskModule.class)
@EnableJpaRepositories(basePackageClasses = TaskModule.class)
public class TaskModule {

	@Bean
	FlywayConfigurationCustomizer taskMigrations() {
		return configuration -> {
			Location[] current = configuration.getLocations();
			Location[] all = Arrays.copyOf(current, current.length + 1);
			all[current.length] = new Location("classpath:db/migration/task");
			configuration.locations(all);
		};
	}

}
