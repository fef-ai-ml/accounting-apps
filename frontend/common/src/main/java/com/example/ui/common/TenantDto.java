package com.example.ui.common;

/** The user's tenant as returned inside {@code GET /api/iam/me}. A UI-side copy, like {@link MeDto}. */
public record TenantDto(String id, String alias, String name, String status) {

	boolean active() {
		return "ACTIVE".equals(status);
	}

}
