package com.example.ui.accounting;

import java.util.Comparator;

/**
 * Lets a combobox search accounts by code or name. ZK calls {@code compare(typedValue, option)} and keeps options
 * that return 0.
 */
public class AccountMatcher implements Comparator<Object> {

	@Override
	public int compare(Object typed, Object option) {
		String key = typed == null ? "" : typed.toString().strip().toLowerCase();
		return ((AccountDto) option).getLabel().toLowerCase().contains(key) ? 0 : 1;
	}

}
