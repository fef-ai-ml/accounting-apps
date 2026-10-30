package com.example.security;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import static org.assertj.core.api.Assertions.assertThat;

class KeycloakJwtAuthenticationConverterTests {

	private static Jwt jwt(Map<String, Object> claims) {
		return Jwt.withTokenValue("token").header("alg", "none").subject("sub-123").claims(c -> c.putAll(claims)).build();
	}

	@Test
	void rolesAreReadFromRealmAccess() {
		Jwt token = jwt(Map.of("realm_access", Map.of("roles", List.of("EDITOR", "VIEWER"))));

		assertThat(KeycloakRoles.of(token)).containsExactlyInAnyOrder("EDITOR", "VIEWER");
	}

	@Test
	void missingRealmAccessMeansNoRoles() {
		assertThat(KeycloakRoles.of(jwt(Map.of("scope", "openid")))).isEmpty();
		assertThat(KeycloakRoles.of(jwt(Map.of("realm_access", "not-a-map")))).isEmpty();
		assertThat(KeycloakRoles.of(jwt(Map.of("realm_access", Map.of("roles", "ADMIN"))))).isEmpty();
	}

	@Test
	void authoritiesAreThePermissionsFromTheResolver() {
		AtomicReference<Jwt> seen = new AtomicReference<>();
		var converter = new KeycloakJwtAuthenticationConverter(token -> {
			seen.set(token);
			return Set.of("task:write", "task:read");
		}, TenantDirectory.none());
		Jwt token = jwt(Map.of("preferred_username", "editor"));

		AbstractAuthenticationToken authentication = converter.convert(token);

		assertThat(seen.get()).isSameAs(token);
		assertThat(authentication.getAuthorities()).extracting(GrantedAuthority::getAuthority)
				.containsExactly("task:read", "task:write");
		assertThat(authentication.getName()).isEqualTo("editor");
		assertThat(authentication.isAuthenticated()).isTrue();
	}

	@Test
	void nameFallsBackToSubject() {
		var converter = new KeycloakJwtAuthenticationConverter(token -> Set.of(), TenantDirectory.none());

		assertThat(converter.convert(jwt(Map.of("scope", "openid"))).getName()).isEqualTo("sub-123");
	}

	@Test
	void denyAllGrantsNothing() {
		var converter = new KeycloakJwtAuthenticationConverter(PermissionResolver.denyAll(), TenantDirectory.none());

		AbstractAuthenticationToken authentication =
				converter.convert(jwt(Map.of("realm_access", Map.of("roles", List.of("ADMIN")))));

		assertThat(authentication.getAuthorities()).isEmpty();
		assertThat(authentication.isAuthenticated()).isTrue();
	}

	@Test
	void carriesTheTenantOfTheTokensOrganization() {
		UUID demo = UUID.fromString("7f3a0c4e-1b2d-4e5f-8a9b-0c1d2e3f4a5b");
		TenantDirectory directory = alias -> "demo".equals(alias) ? Optional.of(demo) : Optional.empty();
		KeycloakJwtAuthenticationConverter converter =
				new KeycloakJwtAuthenticationConverter(PermissionResolver.denyAll(), directory);
		Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject("sub-1")
				.claim("organization", List.of("demo")).build();

		KeycloakAuthentication authentication = (KeycloakAuthentication) converter.convert(jwt);

		assertThat(authentication.getTenantId()).contains(demo);
	}

	@Test
	void unknownOrganizationHasNoTenant() {
		KeycloakJwtAuthenticationConverter converter =
				new KeycloakJwtAuthenticationConverter(PermissionResolver.denyAll(), TenantDirectory.none());
		Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject("sub-1")
				.claim("organization", List.of("demo")).build();

		assertThat(((KeycloakAuthentication) converter.convert(jwt)).getTenantId()).isEmpty();
	}

}
