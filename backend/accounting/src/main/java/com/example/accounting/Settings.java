package com.example.accounting;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.TenantId;

/** One row per tenant, created by setup: its existence means accounting is initialized. */
@Entity
@Table(name = "settings", schema = "accounting")
class Settings {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@TenantId
	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "require_separate_poster", nullable = false)
	private boolean requireSeparatePoster = true;

	@Column(name = "initialized_at")
	private Instant initializedAt;

	@Column(name = "updated_by_sub", length = 100)
	private String updatedBySub;

	@Column(name = "updated_by_name", length = 200)
	private String updatedByName;

	@Column(name = "updated_at")
	private Instant updatedAt;

	protected Settings() {
	}

	Settings(Instant initializedAt) {
		this.initializedAt = initializedAt;
	}

	void requireSeparatePoster(boolean value, Actor actor, Instant at) {
		this.requireSeparatePoster = value;
		this.updatedBySub = actor.sub();
		this.updatedByName = actor.name();
		this.updatedAt = at;
	}

	Long getId() {
		return id;
	}

	boolean isRequireSeparatePoster() {
		return requireSeparatePoster;
	}

	String getUpdatedByName() {
		return updatedByName;
	}

	Instant getUpdatedAt() {
		return updatedAt;
	}

}
