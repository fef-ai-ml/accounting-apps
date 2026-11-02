package com.example.accounting;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.TenantId;

@Entity
@Table(name = "account", schema = "accounting")
public class Account {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@TenantId
	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(nullable = false, length = 20)
	private String code;

	@Column(nullable = false, length = 200)
	private String name;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private AccountType type;

	@Column(name = "parent_id")
	private Long parentId;

	@Column(nullable = false)
	private boolean postable;

	@Column(nullable = false)
	private boolean active;

	protected Account() {
	}

	Account(String code, String name, AccountType type, Long parentId, boolean postable, boolean active) {
		update(code, name, type, parentId, postable, active);
	}

	void update(String code, String name, AccountType type, Long parentId, boolean postable, boolean active) {
		this.code = code;
		this.name = name;
		this.type = type;
		this.parentId = parentId;
		this.postable = postable;
		this.active = active;
	}

	public Long getId() {
		return id;
	}

	public String getCode() {
		return code;
	}

	public String getName() {
		return name;
	}

	public AccountType getType() {
		return type;
	}

	public Long getParentId() {
		return parentId;
	}

	public boolean isPostable() {
		return postable;
	}

	public boolean isActive() {
		return active;
	}

}
