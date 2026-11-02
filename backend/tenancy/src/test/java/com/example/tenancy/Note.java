package com.example.tenancy;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.TenantId;

@Entity
@Table(name = "note", schema = "tenancy_test")
class Note {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@TenantId
	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(nullable = false, length = 100)
	private String text;

	protected Note() {
	}

	Note(String text) {
		this.text = text;
	}

	String getText() {
		return text;
	}

	UUID getTenantId() {
		return tenantId;
	}

}
