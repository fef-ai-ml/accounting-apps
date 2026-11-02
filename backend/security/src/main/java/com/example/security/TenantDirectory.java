package com.example.security;

import java.util.UUID;

/**
 * Confirms that a tenant id (the Keycloak organization id from the token) belongs to an onboarded, active tenant. The
 * {@code iam} module implements it.
 */
public interface TenantDirectory {

	/** True only when the tenant is known and ACTIVE. */
	boolean isActive(UUID tenantId);

	/** Knows no tenant; used when a deployment has no directory, so a misconfiguration fails closed. */
	static TenantDirectory none() {
		return tenantId -> false;
	}

}
