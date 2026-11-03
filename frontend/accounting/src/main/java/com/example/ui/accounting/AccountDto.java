package com.example.ui.accounting;

/** An account as returned by the REST API. */
public record AccountDto(Long id, String code, String name, String type, Long parentId, boolean postable,
		boolean active) {

	// JavaBean getters: ZK's EL cannot read record accessors.

	public Long getId() {
		return id;
	}

	public String getCode() {
		return code;
	}

	public String getName() {
		return name;
	}

	public String getType() {
		return type;
	}

	public boolean isPostable() {
		return postable;
	}

	public boolean isActive() {
		return active;
	}

	public String getLabel() {
		return code + " " + name;
	}

}
