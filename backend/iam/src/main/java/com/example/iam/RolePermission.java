package com.example.iam;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** One row of {@code iam.role_permission}. {@code iam.permission} is not mapped: nothing reads it as an entity. */
@Entity
@Table(name = "role_permission", schema = "iam")
class RolePermission {

	@EmbeddedId
	private RolePermissionId id;

	protected RolePermission() {
	}

	RolePermissionId getId() {
		return id;
	}

}
