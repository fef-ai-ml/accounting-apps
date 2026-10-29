package com.example.security;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.security.oauth2.jwt.Jwt;

/** Reads Keycloak realm roles from the {@code realm_access.roles} claim of an access token. */
public final class KeycloakRoles {

	private KeycloakRoles() {
	}

	/** The realm roles in the token; empty when the claim is missing or not shaped as Keycloak writes it. */
	public static Set<String> of(Jwt jwt) {
		if (!(jwt.getClaims().get("realm_access") instanceof Map<?, ?> realmAccess)
				|| !(realmAccess.get("roles") instanceof Collection<?> roles)) {
			return Set.of();
		}
		return roles.stream()
				.filter(String.class::isInstance)
				.map(String.class::cast)
				.collect(Collectors.toUnmodifiableSet());
	}

}
