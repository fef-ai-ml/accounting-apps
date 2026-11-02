package com.example.accounting;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Global, read-only: the chart of accounts every tenant copies at setup. */
@Entity
@Table(name = "coa_template", schema = "accounting")
class CoaTemplate {

	@Id
	@Column(length = 20)
	private String code;

	@Column(nullable = false, length = 200)
	private String name;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private AccountType type;

	@Column(name = "parent_code", length = 20)
	private String parentCode;

	@Column(nullable = false)
	private boolean postable;

	protected CoaTemplate() {
	}

	String getCode() {
		return code;
	}

	String getName() {
		return name;
	}

	AccountType getType() {
		return type;
	}

	String getParentCode() {
		return parentCode;
	}

	boolean isPostable() {
		return postable;
	}

}
