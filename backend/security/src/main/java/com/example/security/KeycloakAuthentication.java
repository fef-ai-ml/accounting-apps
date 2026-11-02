package com.example.security;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * A validated Keycloak access token with the tenant it was resolved to once, at authentication time. Data access
 * reads the tenant from here instead of looking it up again (a lookup would itself need a tenant).
 */
public class KeycloakAuthentication extends JwtAuthenticationToken {

	private final UUID tenantId;

	public KeycloakAuthentication(Jwt jwt, Collection<? extends GrantedAuthority> authorities, String name,
			UUID tenantId) {
		super(jwt, authorities, name);
		this.tenantId = tenantId;
	}

	/** Empty when the token names no organization, several, an unknown one or a suspended one. */
	public Optional<UUID> getTenantId() {
		return Optional.ofNullable(tenantId);
	}

}
