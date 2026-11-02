package com.example.accounting;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.Location;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;

class AccountingModuleTests {

	private static String[] locationsAfter(String... configured) {
		FluentConfiguration configuration = Flyway.configure();
		if (configured.length > 0) {
			configuration.locations(configured);
		}
		new AccountingModule().accountingMigrations().customize(configuration);
		return Arrays.stream(configuration.getLocations()).map(Location::getDescriptor).toArray(String[]::new);
	}

	@Test
	void replacesTheDefaultLocationWithItsOwnFolder() {
		assertThat(locationsAfter()).containsExactly("classpath:db/migration/accounting");
	}

	@Test
	void keepsOtherConfiguredLocations() {
		assertThat(locationsAfter("classpath:db/test/accounting"))
				.containsExactlyInAnyOrder("classpath:db/test/accounting", "classpath:db/migration/accounting");
	}

}
