package com.example.iam;

import java.util.UUID;

/** Cached, detached copy of a tenant row. */
record TenantInfo(UUID id, String alias, String name, String status) {

	static TenantInfo of(Tenant tenant) {
		return new TenantInfo(tenant.getId(), tenant.getAlias(), tenant.getName(), tenant.getStatus());
	}

	boolean active() {
		return "ACTIVE".equals(status);
	}

}
