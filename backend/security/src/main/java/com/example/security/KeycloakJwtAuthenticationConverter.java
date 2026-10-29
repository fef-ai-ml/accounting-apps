package com.example.security;

import java.util.List;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Builds the authentication for a Keycloak access token: authorities are permission codes (e.g. {@code task:write})
 * from the {@link PermissionResolver}, the name is {@code preferred_username}.
 */
public class KeycloakJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

	private final PermissionResolver permissionResolver;

	public KeycloakJwtAuthenticationConverter(PermissionResolver permissionResolver) {
		this.permissionResolver = permissionResolver;
	}

	@Override
	public AbstractAuthenticationToken convert(Jwt jwt) {
		List<GrantedAuthority> authorities = permissionResolver.permissionsFor(jwt).stream()
				.sorted()
				.<GrantedAuthority>map(SimpleGrantedAuthority::new)
				.toList();
		String username = jwt.getClaimAsString("preferred_username");
		return new JwtAuthenticationToken(jwt, authorities, username != null ? username : jwt.getSubject());
	}

}
