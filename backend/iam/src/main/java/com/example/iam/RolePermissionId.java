package com.example.iam;

import java.io.Serializable;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/** Primary key of {@code iam.role_permission}: a Keycloak realm role and one permission code it grants. */
@Embeddable
record RolePermissionId(@Column(name = "role") String role, @Column(name = "permission_code") String permissionCode)
		implements Serializable {
}
