package com.example.iam;

import java.util.UUID;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A customer company: one Keycloak organization. A global table, so no {@code @TenantId}. */
@Entity
@Table(name = "tenant", schema = "iam")
class Tenant {

	@Id
	private UUID id;

	private String alias;

	private String name;

	private String status;

	protected Tenant() {
	}

	UUID getId() {
		return id;
	}

	String getAlias() {
		return alias;
	}

	String getName() {
		return name;
	}

	String getStatus() {
		return status;
	}

}
