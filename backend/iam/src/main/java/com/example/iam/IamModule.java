package com.example.iam;

import java.util.Arrays;

import org.flywaydb.core.api.Location;
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
	FlywayConfigurationCustomizer iamMigrations() {
		return configuration -> {
			Location[] current = configuration.getLocations();
			Location[] all = Arrays.copyOf(current, current.length + 1);
			all[current.length] = new Location("classpath:db/migration/iam");
			configuration.locations(all);
		};
	}

}
