package com.example.accounting;

public record AccountResponse(Long id, String code, String name, AccountType type, Long parentId, boolean postable,
		boolean active) {

	static AccountResponse from(Account account) {
		return new AccountResponse(account.getId(), account.getCode(), account.getName(), account.getType(),
				account.getParentId(), account.isPostable(), account.isActive());
	}

}
