package com.example.security;

import java.util.List;
import java.util.Map;
import java.util.Set;
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
		});
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
		var converter = new KeycloakJwtAuthenticationConverter(token -> Set.of());

		assertThat(converter.convert(jwt(Map.of("scope", "openid"))).getName()).isEqualTo("sub-123");
	}

	@Test
	void denyAllGrantsNothing() {
		var converter = new KeycloakJwtAuthenticationConverter(PermissionResolver.denyAll());

		AbstractAuthenticationToken authentication =
				converter.convert(jwt(Map.of("realm_access", Map.of("roles", List.of("ADMIN")))));

		assertThat(authentication.getAuthorities()).isEmpty();
		assertThat(authentication.isAuthenticated()).isTrue();
	}

}
