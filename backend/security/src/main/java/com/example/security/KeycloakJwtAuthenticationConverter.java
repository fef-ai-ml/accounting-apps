package com.example.security;

import java.util.List;
import java.util.UUID;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Builds the authentication for a Keycloak access token: authorities are permission codes (e.g. {@code task:write})
 * from the {@link PermissionResolver}, the name is {@code preferred_username},
 * and the tenant of the token's Keycloak organization.
 */
public class KeycloakJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

	private final PermissionResolver permissionResolver;

	private final TenantDirectory tenantDirectory;

	public KeycloakJwtAuthenticationConverter(PermissionResolver permissionResolver, TenantDirectory tenantDirectory) {
		this.permissionResolver = permissionResolver;
		this.tenantDirectory = tenantDirectory;
	}

	@Override
	public AbstractAuthenticationToken convert(Jwt jwt) {
		List<GrantedAuthority> authorities = permissionResolver.permissionsFor(jwt).stream()
				.sorted()
				.<GrantedAuthority>map(SimpleGrantedAuthority::new)
				.toList();
		String username = jwt.getClaimAsString("preferred_username");
		UUID tenantId = KeycloakTenant.alias(jwt).flatMap(tenantDirectory::tenantIdOf).orElse(null);
		return new KeycloakAuthentication(jwt, authorities, username != null ? username : jwt.getSubject(), tenantId);
	}

}
