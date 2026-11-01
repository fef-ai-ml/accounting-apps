package com.example.iam;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.Location;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;

class IamModuleTests {

	private static String[] locationsAfter(boolean demoTenants, String... configured) {
		FluentConfiguration configuration = Flyway.configure();
		if (configured.length > 0) {
			configuration.locations(configured);
		}
		new IamModule().iamMigrations(demoTenants).customize(configuration);
		return Arrays.stream(configuration.getLocations()).map(Location::getDescriptor).toArray(String[]::new);
	}

	@Test
	void replacesTheDefaultLocationWithItsOwnFolder() {
		assertThat(locationsAfter(false)).containsExactly("classpath:db/migration/iam");
	}

	@Test
	void addsTheDemoFolderOnlyWhenDemoTenantsAreOn() {
		assertThat(locationsAfter(true)).containsExactlyInAnyOrder("classpath:db/migration/iam", "classpath:db/demo/iam");
	}

	@Test
	void keepsOtherConfiguredLocations() {
		assertThat(locationsAfter(false, "classpath:db/test/iam"))
				.containsExactlyInAnyOrder("classpath:db/test/iam", "classpath:db/migration/iam");
	}

}
