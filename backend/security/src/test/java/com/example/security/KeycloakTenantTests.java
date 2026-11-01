package com.example.security;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import static org.assertj.core.api.Assertions.assertThat;

class KeycloakTenantTests {

	private static final UUID DEMO = UUID.fromString("7f3a0c4e-1b2d-4e5f-8a9b-0c1d2e3f4a5b");

	private static Jwt token(String claim, Object value) {
		return Jwt.withTokenValue("t").header("alg", "none").subject("sub-1").claim(claim, value).build();
	}

	private static Jwt tenant(Object value) {
		return token("tenant", value);
	}

	@Test
	void theIdOfTheSingleOrganization() {
		// Shape written by the "tenant" membership mapper with "add organization id".
		assertThat(KeycloakTenant.id(tenant(Map.of("demo", Map.of("id", DEMO.toString()))))).contains(DEMO);
	}

	@Test
	void aliasesAloneMeanNoTenant() {
		assertThat(KeycloakTenant.id(token("organization", List.of("demo")))).isEmpty();
		assertThat(KeycloakTenant.id(token("organization", Map.of("demo", Map.of("id", DEMO.toString()))))).isEmpty();
		assertThat(KeycloakTenant.id(tenant(List.of("demo")))).isEmpty();
	}

	@Test
	void severalOrNoOrganizationsMeanNoTenant() {
		assertThat(KeycloakTenant.id(tenant(Map.of()))).isEmpty();
		assertThat(KeycloakTenant.id(tenant(Map.of("demo", Map.of("id", DEMO.toString()),
				"acme", Map.of("id", UUID.randomUUID().toString()))))).isEmpty();
	}

	@Test
	void missingBlankOrInvalidIdMeansNoTenant() {
		assertThat(KeycloakTenant.id(tenant(Map.of("demo", Map.of())))).isEmpty();
		assertThat(KeycloakTenant.id(tenant(Map.of("demo", Map.of("id", ""))))).isEmpty();
		assertThat(KeycloakTenant.id(tenant(Map.of("demo", Map.of("id", " "))))).isEmpty();
		assertThat(KeycloakTenant.id(tenant(Map.of("demo", Map.of("id", "7f3a"))))).isEmpty();
		assertThat(KeycloakTenant.id(tenant(Map.of("demo", Map.of("id", "not-a-uuid"))))).isEmpty();
		assertThat(KeycloakTenant.id(tenant(Map.of("demo", Map.of("id", "1-2-3-4-5"))))).as("UUID.fromString is lenient")
				.isEmpty();
		assertThat(KeycloakTenant.id(tenant(Map.of("demo", Map.of("id", 42))))).isEmpty();
		assertThat(KeycloakTenant.id(tenant(Map.of("demo", DEMO.toString())))).isEmpty();
	}

	@Test
	void blankOrNonStringAliasMeansNoTenant() {
		assertThat(KeycloakTenant.id(tenant(Map.of(" ", Map.of("id", DEMO.toString()))))).isEmpty();
		assertThat(KeycloakTenant.id(tenant(Map.of(42, Map.of("id", DEMO.toString()))))).isEmpty();
		Map<String, Object> nullValue = new HashMap<>();
		nullValue.put("demo", null);
		assertThat(KeycloakTenant.id(tenant(nullValue))).isEmpty();
	}

	@Test
	void missingOrWrongTypeClaimMeansNoTenant() {
		assertThat(KeycloakTenant.id(token("scope", "openid"))).isEmpty();
		assertThat(KeycloakTenant.id(tenant(DEMO.toString()))).isEmpty();
		assertThat(KeycloakTenant.id(tenant(42))).isEmpty();
	}

	@Test
	void activeTenantNeedsTheDirectoryToConfirmIt() {
		Jwt jwt = tenant(Map.of("demo", Map.of("id", DEMO.toString())));

		assertThat(KeycloakTenant.activeTenant(jwt, DEMO::equals)).contains(DEMO);
		assertThat(KeycloakTenant.activeTenant(jwt, TenantDirectory.none())).isEmpty();
		assertThat(KeycloakTenant.activeTenant(token("scope", "openid"), id -> true)).isEmpty();
	}

}
