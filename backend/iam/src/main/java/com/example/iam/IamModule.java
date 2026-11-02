package com.example.iam;

import java.util.Arrays;
import java.util.stream.Stream;

import org.flywaydb.core.api.Location;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import com.example.security.SecurityModule;

/**
 * Entry point of the identity-and-access context. A deployable app includes it with {@code @Import(IamModule.class)}.
 *
 * <p>Keycloak says who the user is and which realm roles they hold; this module owns which permissions each role
 * grants (Postgres schema {@code iam}, migrations in {@code db/migration/iam}) and provides the deployment's
 * {@link com.example.security.PermissionResolver}.
 */
@Configuration(proxyBeanMethods = false)
@Import(SecurityModule.class)
@ComponentScan(basePackageClasses = IamModule.class)
@EntityScan(basePackageClasses = IamModule.class)
@EnableJpaRepositories(basePackageClasses = IamModule.class)
public class IamModule {

	@Bean
	FlywayConfigurationCustomizer iamMigrations(@Value("${iam.demo-tenants:false}") boolean demoTenants) {
		// Boot's default db/migration scans recursively, so Flyway would discard this folder and run every module's.
		return configuration -> configuration.locations(Stream.concat(
				Arrays.stream(configuration.getLocations()).filter(l -> !l.getDescriptor().equals("classpath:db/migration")),
				Stream.concat(Stream.of(new Location("classpath:db/migration/iam")),
						demoTenants ? Stream.of(new Location("classpath:db/demo/iam")) : Stream.empty()))
				.toArray(Location[]::new));
	}

}
