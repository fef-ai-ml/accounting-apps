package com.example.ui.accounting;

public record AccountRow(AccountDto account, int depth) {

	public AccountDto getAccount() {
		return account;
	}

	public String getIndentStyle() {
		return "padding-left: " + depth * 20 + "px";
	}

}
