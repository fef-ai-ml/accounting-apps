package com.example.iam;

import java.time.Duration;
import java.util.Set;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import com.example.security.KeycloakRoles;
import com.example.security.PermissionResolver;

/**
 * Resolves permissions from the {@code iam} tables. Results are cached per role set for 60 seconds, so a changed
 * mapping reaches running instances within a minute without a restart.
 */
@Component
public class DbPermissionResolver implements PermissionResolver {

	private final RolePermissionRepository repository;

	private final Cache<Set<String>, Set<String>> cache = Caffeine.newBuilder()
			.expireAfterWrite(Duration.ofSeconds(60))
			.maximumSize(1_000)
			.build();

	DbPermissionResolver(RolePermissionRepository repository) {
		this.repository = repository;
	}

	@Override
	public Set<String> permissionsFor(Jwt jwt) {
		return permissionsForRoles(KeycloakRoles.of(jwt));
	}

	Set<String> permissionsForRoles(Set<String> roles) {
		// Set equality ignores order, so {EDITOR, VIEWER} and {VIEWER, EDITOR} share one entry.
		return cache.get(Set.copyOf(roles), key -> key.isEmpty()
				? Set.of() // "IN ()" is not valid SQL
				: Set.copyOf(repository.findPermissionCodesByRoleIn(key)));
	}

}
