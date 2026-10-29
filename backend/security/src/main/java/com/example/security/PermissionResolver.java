package com.example.security;

import java.util.Set;

import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Turns a validated access token into the permission codes ({@code <module>:<action>}) its holder has.
 *
 * <p>It takes the whole token, not just role names, so an implementation in a split-out service can relay the user's
 * token to the {@code iam} module instead of reading the database.
 */
public interface PermissionResolver {

	Set<String> permissionsFor(Jwt jwt);

	/** Grants nothing; used when a deployment has no resolver, so a misconfiguration fails closed. */
	static PermissionResolver denyAll() {
		return jwt -> Set.of();
	}

}
