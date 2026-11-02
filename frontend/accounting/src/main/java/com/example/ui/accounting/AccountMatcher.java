package com.example.ui.accounting;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

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

	/** The account a person meant by this text: the exact label, else the only option the combobox would keep. */
	static Optional<AccountDto> find(String text, List<AccountDto> options) {
		if (text == null || text.isBlank()) {
			return Optional.empty();
		}
		String typed = text.strip();
		Optional<AccountDto> exact = options.stream().filter(option -> option.getLabel().equals(typed)).findFirst();
		if (exact.isPresent()) {
			return exact;
		}
		AccountMatcher matcher = new AccountMatcher();
		List<AccountDto> matches = options.stream().filter(option -> matcher.compare(typed, option) == 0).toList();
		return matches.size() == 1 ? Optional.of(matches.get(0)) : Optional.empty();
	}

}
