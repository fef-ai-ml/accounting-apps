package com.example.accounting;

public enum AccountType {

	ASSET, LIABILITY, EQUITY, REVENUE, EXPENSE;

	/** Reports show a balance on the account's normal side. */
	boolean debitNormal() {
		return this == ASSET || this == EXPENSE;
	}

}
