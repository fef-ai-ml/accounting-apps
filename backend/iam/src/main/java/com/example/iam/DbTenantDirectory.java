package com.example.iam;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;

import com.example.security.TenantDirectory;

/**
 * Tenants from {@code iam.tenant}, cached per alias for 60 seconds: suspending or onboarding a tenant reaches running
 * instances within a minute.
 */
@Component
public class DbTenantDirectory implements TenantDirectory {

	private final TenantRepository repository;

	private final Cache<String, Optional<TenantInfo>> cache = Caffeine.newBuilder()
			.expireAfterWrite(Duration.ofSeconds(60))
			.maximumSize(10_000)
			.build();

	DbTenantDirectory(TenantRepository repository) {
		this.repository = repository;
	}

	@Override
	public Optional<UUID> tenantIdOf(String alias) {
		return find(alias).filter(TenantInfo::active).map(TenantInfo::id);
	}

	/** The tenant whatever its status, for showing it to the user. */
	Optional<TenantInfo> find(String alias) {
		return cache.get(alias, key -> repository.findByAlias(key).map(TenantInfo::of));
	}

}
