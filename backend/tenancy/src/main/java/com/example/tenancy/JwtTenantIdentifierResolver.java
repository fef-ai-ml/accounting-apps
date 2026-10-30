package com.example.tenancy;

import java.util.UUID;

import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import org.springframework.security.core.context.SecurityContextHolder;

import com.example.security.KeycloakAuthentication;

/**
 * Hibernate's current tenant: the one resolved for the request's {@link KeycloakAuthentication}. Anything else
 * (no authentication, no tenant, another authentication type) is {@link #ROOT}: Hibernate does not filter, which
 * keeps global tables such as {@code iam.*} readable, and {@link RlsConnectionProvider} sets no tenant on the
 * connection, so row-level security shows no tenant rows and rejects tenant writes.
 */
public final class JwtTenantIdentifierResolver implements CurrentTenantIdentifierResolver<UUID> {

	public static final UUID ROOT = new UUID(0L, 0L);

	@Override
	public UUID resolveCurrentTenantIdentifier() {
		return SecurityContextHolder.getContext().getAuthentication() instanceof KeycloakAuthentication authentication
				? authentication.getTenantId().orElse(ROOT)
				: ROOT;
	}

	@Override
	public boolean validateExistingCurrentSessions() {
		return false;
	}

	@Override
	public boolean isRoot(UUID tenantId) {
		return ROOT.equals(tenantId);
	}

}
