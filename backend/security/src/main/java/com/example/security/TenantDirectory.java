package com.example.security;

import java.util.Optional;
import java.util.UUID;

/**
 * Translates a Keycloak organization alias into the tenant id (the organization's UUID) of an active tenant. The
 * {@code iam} module implements it; aliases are a safe key because Keycloak does not allow renaming them.
 */
public interface TenantDirectory {

	/** The tenant id, or empty when the alias is unknown or the tenant is not active. */
	Optional<UUID> tenantIdOf(String alias);

	/** Knows no tenant; used when a deployment has no directory, so a misconfiguration fails closed. */
	static TenantDirectory none() {
		return alias -> Optional.empty();
	}

}
