package com.example.security;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.security.oauth2.jwt.Jwt;

/** Reads the tenant's Keycloak organization alias from an access token. */
public final class KeycloakTenant {

	private KeycloakTenant() {
	}

	/**
	 * The single organization alias in the {@code organization} claim. Keycloak writes a list of aliases by default and
	 * an object keyed by alias when the mapper adds ids. No claim, several organizations or any other shape mean "no
	 * tenant": a user belongs to exactly one tenant.
	 */
	public static Optional<String> alias(Jwt jwt) {
		Collection<?> aliases = switch (jwt.getClaims().get("organization")) {
			case Collection<?> list -> list;
			case Map<?, ?> map -> map.keySet();
			case null, default -> List.of();
		};
		return aliases.size() == 1 && aliases.iterator().next() instanceof String alias && !alias.isBlank()
				? Optional.of(alias)
				: Optional.empty();
	}

}
