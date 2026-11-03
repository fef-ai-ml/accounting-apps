package com.example.security;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import static org.assertj.core.api.Assertions.assertThat;

class KeycloakJwtAuthenticationConverterTests {

	private static final UUID DEMO = UUID.fromString("7f3a0c4e-1b2d-4e5f-8a9b-0c1d2e3f4a5b");

	private static Map<String, Object> demoTenant() {
		return Map.of("demo", Map.of("id", DEMO.toString()));
	}

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
		}, DEMO::equals);
		Jwt token = jwt(Map.of("preferred_username", "editor", "tenant", demoTenant()));

		AbstractAuthenticationToken authentication = converter.convert(token);

		assertThat(seen.get()).isSameAs(token);
		assertThat(authentication.getAuthorities()).extracting(GrantedAuthority::getAuthority)
				.containsExactly("task:read", "task:write");
		assertThat(authentication.getName()).isEqualTo("editor");
		assertThat(authentication.isAuthenticated()).isTrue();
	}

	@Test
	void noActiveTenantGetsNoAuthoritiesAndTheResolverIsNotAsked() {
		AtomicReference<Jwt> asked = new AtomicReference<>();
		var converter = new KeycloakJwtAuthenticationConverter(token -> {
			asked.set(token);
			return Set.of("task:read");
		}, DEMO::equals);

		var noClaim = converter.convert(jwt(Map.of("preferred_username", "u")));
		var unknown = converter.convert(jwt(Map.of("tenant", Map.of("other", Map.of("id", UUID.randomUUID().toString())))));

		assertThat(noClaim.getAuthorities()).isEmpty();
		assertThat(unknown.getAuthorities()).isEmpty();
		assertThat(noClaim.isAuthenticated()).isTrue();
		assertThat(asked.get()).isNull();
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
		KeycloakJwtAuthenticationConverter converter =
				new KeycloakJwtAuthenticationConverter(PermissionResolver.denyAll(), demo::equals);
		Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject("sub-1")
				.claim("tenant", Map.of("demo", Map.of("id", demo.toString()))).build();

		KeycloakAuthentication authentication = (KeycloakAuthentication) converter.convert(jwt);

		assertThat(authentication.getTenantId()).contains(demo);
	}

	@Test
	void inactiveOrUnknownOrganizationHasNoTenant() {
		KeycloakJwtAuthenticationConverter converter =
				new KeycloakJwtAuthenticationConverter(PermissionResolver.denyAll(), TenantDirectory.none());
		Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject("sub-1")
				.claim("tenant", Map.of("demo", Map.of("id", "7f3a0c4e-1b2d-4e5f-8a9b-0c1d2e3f4a5b"))).build();

		assertThat(((KeycloakAuthentication) converter.convert(jwt)).getTenantId()).isEmpty();
	}

}
