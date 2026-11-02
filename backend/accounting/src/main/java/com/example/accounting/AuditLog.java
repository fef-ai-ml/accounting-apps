package com.example.accounting;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.ColumnTransformer;
import org.hibernate.annotations.TenantId;

@Entity
@Table(name = "audit_log", schema = "accounting")
class AuditLog {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@TenantId
	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 30)
	private AuditAction action;

	@Column(nullable = false, length = 30)
	private String entity;

	@Column(name = "entity_id")
	private Long entityId;

	@Column(name = "actor_sub", nullable = false, length = 100)
	private String actorSub;

	@Column(name = "actor_name", nullable = false, length = 200)
	private String actorName;

	@Column(name = "at", nullable = false)
	private Instant at;

	@ColumnTransformer(write = "cast(? as jsonb)")
	@Column(columnDefinition = "jsonb")
	private String details;

	protected AuditLog() {
	}

	AuditLog(AuditAction action, String entity, Long entityId, Actor actor, Instant at, String details) {
		this.action = action;
		this.entity = entity;
		this.entityId = entityId;
		this.actorSub = actor.sub();
		this.actorName = actor.name();
		this.at = at;
		this.details = details;
	}

}
