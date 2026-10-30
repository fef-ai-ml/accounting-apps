package com.example.iam;

import java.time.Duration;
import java.util.Set;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import com.example.security.KeycloakRoles;
import com.example.security.KeycloakTenant;
import com.example.security.PermissionResolver;
import com.example.security.TenantDirectory;

/**
 * Resolves permissions from the {@code iam} tables for users of an active tenant. Results are cached per role set
 * for 60 seconds, so a changed mapping reaches running instances within a minute without a restart.
 */
@Component
public class DbPermissionResolver implements PermissionResolver {

	private final RolePermissionRepository repository;

	private final TenantDirectory tenants;

	private final Cache<Set<String>, Set<String>> cache = Caffeine.newBuilder()
			.expireAfterWrite(Duration.ofSeconds(60))
			.maximumSize(1_000)
			.build();

	DbPermissionResolver(RolePermissionRepository repository, TenantDirectory tenants) {
		this.repository = repository;
		this.tenants = tenants;
	}

	@Override
	public Set<String> permissionsFor(Jwt jwt) {
		// Roles act inside the user's tenant only; without an active tenant they grant nothing.
		boolean activeTenant = KeycloakTenant.alias(jwt).flatMap(tenants::tenantIdOf).isPresent();
		return activeTenant ? permissionsForRoles(KeycloakRoles.of(jwt)) : Set.of();
	}

	Set<String> permissionsForRoles(Set<String> roles) {
		// Set equality ignores order, so {EDITOR, VIEWER} and {VIEWER, EDITOR} share one entry.
		return cache.get(Set.copyOf(roles), key -> key.isEmpty()
				? Set.of() // "IN ()" is not valid SQL
				: Set.copyOf(repository.findPermissionCodesByRoleIn(key)));
	}

}
