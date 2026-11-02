package com.example.iam;

import java.util.UUID;

public record TenantResponse(UUID id, String alias, String name, String status) {

	static TenantResponse of(TenantInfo tenant) {
		return new TenantResponse(tenant.id(), tenant.alias(), tenant.name(), tenant.status());
	}

}
