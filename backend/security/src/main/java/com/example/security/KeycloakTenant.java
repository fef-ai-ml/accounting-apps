package com.example.security;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.security.oauth2.jwt.Jwt;

/** Reads the tenant, the Keycloak organization id, from an access token. */
public final class KeycloakTenant {

	private KeycloakTenant() {
	}

	/**
	 * The id of the single organization in the {@code tenant} claim, written by the {@code zk-shell} client's
	 * membership mapper as {@code {"<alias>": {"id": "<organization id>"}}}. The id and not the alias is the tenant: a
	 * deleted organization's alias can be re-created for another company, its id cannot. No claim, several
	 * organizations or any other shape mean "no tenant": a user belongs to exactly one tenant.
	 */
	public static Optional<UUID> id(Jwt jwt) {
		if (!(jwt.getClaims().get("tenant") instanceof Map<?, ?> organizations) || organizations.size() != 1) {
			return Optional.empty();
		}
		Map.Entry<?, ?> organization = organizations.entrySet().iterator().next();
		if (!(organization.getKey() instanceof String alias) || alias.isBlank()
				|| !(organization.getValue() instanceof Map<?, ?> attributes)
				|| !(attributes.get("id") instanceof String id)) {
			return Optional.empty();
		}
		try {
			UUID tenantId = UUID.fromString(id);
			// UUID.fromString accepts shortened forms such as "1-2-3-4-5"; only the canonical form is an id.
			return tenantId.toString().equalsIgnoreCase(id) ? Optional.of(tenantId) : Optional.empty();
		}
		catch (IllegalArgumentException ex) {
			return Optional.empty();
		}
	}

	/** The token's tenant when the directory confirms it is active. */
	public static Optional<UUID> activeTenant(Jwt jwt, TenantDirectory directory) {
		return id(jwt).filter(directory::isActive);
	}

}
