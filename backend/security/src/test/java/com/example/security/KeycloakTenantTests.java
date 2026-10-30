package com.example.security;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import static org.assertj.core.api.Assertions.assertThat;

class KeycloakTenantTests {

	private static Jwt token(Object organization) {
		Jwt.Builder builder = Jwt.withTokenValue("t").header("alg", "none").subject("sub-1");
		if (organization != null) {
			builder.claim("organization", organization);
		}
		return builder.build();
	}

	@Test
	void singleAliasInAList() {
		assertThat(KeycloakTenant.alias(token(List.of("demo")))).contains("demo");
	}

	@Test
	void singleAliasAsObjectKey() {
		// Shape produced when the mapper's "add organization id" option is on.
		assertThat(KeycloakTenant.alias(token(Map.of("demo", Map.of("id", "7f3a"))))).contains("demo");
	}

	@Test
	void missingEmptyOrSeveralMeansNoTenant() {
		assertThat(KeycloakTenant.alias(token(null))).isEmpty();
		assertThat(KeycloakTenant.alias(token(List.of()))).isEmpty();
		assertThat(KeycloakTenant.alias(token(List.of("demo", "acme")))).isEmpty();
	}

	@Test
	void malformedClaimMeansNoTenant() {
		assertThat(KeycloakTenant.alias(token("demo"))).isEmpty();
		assertThat(KeycloakTenant.alias(token(List.of(42)))).isEmpty();
		assertThat(KeycloakTenant.alias(token(List.of(" ")))).isEmpty();
	}

}
