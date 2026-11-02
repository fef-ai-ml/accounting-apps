package com.example.iam;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;

import com.example.security.TenantDirectory;

/**
 * Tenants from {@code iam.tenant}, keyed by Keycloak organization id and cached for 60 seconds: suspending or onboarding
 * a tenant reaches running instances within a minute.
 */
@Component
public class DbTenantDirectory implements TenantDirectory {

	private final TenantRepository repository;

	private final Cache<UUID, Optional<TenantInfo>> cache = Caffeine.newBuilder()
			.expireAfterWrite(Duration.ofSeconds(60))
			.maximumSize(10_000)
			.build();

	DbTenantDirectory(TenantRepository repository) {
		this.repository = repository;
	}

	@Override
	public boolean isActive(UUID tenantId) {
		return find(tenantId).filter(TenantInfo::active).isPresent();
	}

	/** The tenant whatever its status, for showing it to the user. */
	Optional<TenantInfo> find(UUID tenantId) {
		return cache.get(tenantId, key -> repository.findById(key).map(TenantInfo::of));
	}

}
