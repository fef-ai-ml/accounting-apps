package com.example.accounting;

import java.time.Clock;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.stream.Stream;

import org.flywaydb.core.api.Location;
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import com.example.common.CommonModule;
import com.example.security.SecurityModule;
import com.example.tenancy.TenancyModule;

/**
 * Entry point of the accounting bounded context. A deployable app includes it with
 * {@code @Import(AccountingModule.class)}. The module owns Postgres schema {@code accounting} and its migrations
 * ({@code db/migration/accounting}); every tenant table is isolated as described in {@code TenancyModule}.
 */
@Configuration(proxyBeanMethods = false)
@Import({ CommonModule.class, SecurityModule.class, TenancyModule.class })
@ComponentScan(basePackageClasses = AccountingModule.class)
@EntityScan(basePackageClasses = AccountingModule.class)
@EnableJpaRepositories(basePackageClasses = AccountingModule.class)
public class AccountingModule {

	@Bean
	FlywayConfigurationCustomizer accountingMigrations() {
		// Boot's default db/migration scans recursively, so Flyway would discard this folder and run every module's.
		return configuration -> configuration.locations(Stream.concat(
				Arrays.stream(configuration.getLocations()).filter(l -> !l.getDescriptor().equals("classpath:db/migration")),
				Stream.of(new Location("classpath:db/migration/accounting"))).toArray(Location[]::new));
	}

	/** "Today" for periods and default dates: the companies this is sold to work on Indonesian time. */
	@Bean
	Clock accountingClock() {
		return Clock.system(ZoneId.of("Asia/Jakarta"));
	}

}
