package com.example.accounting;

import com.example.common.ResourceNotFoundException;

public class AccountingNotFoundException extends ResourceNotFoundException {

	public AccountingNotFoundException(String what) {
		super(what + " not found");
	}

}
