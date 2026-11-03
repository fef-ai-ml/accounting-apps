package com.example.ui.accounting;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Orders the flat account list so each account follows its parent, with its depth for indentation. */
final class AccountTree {

	private AccountTree() {
	}

	static List<AccountRow> rows(List<AccountDto> accounts) {
		Set<Long> ids = accounts.stream().map(AccountDto::id).collect(Collectors.toSet());
		// An account whose parent is not in the list is still shown, as a root.
		Map<Long, List<AccountDto>> children = accounts.stream()
				.filter(account -> ids.contains(account.parentId()))
				.collect(Collectors.groupingBy(AccountDto::parentId));
		List<AccountRow> rows = new ArrayList<>();
		accounts.stream()
				.filter(account -> !ids.contains(account.parentId()))
				.sorted(Comparator.comparing(AccountDto::code))
				.forEach(root -> add(root, 0, children, rows));
		return rows;
	}

	private static void add(AccountDto account, int depth, Map<Long, List<AccountDto>> children, List<AccountRow> rows) {
		rows.add(new AccountRow(account, depth));
		children.getOrDefault(account.id(), List.of()).stream()
				.sorted(Comparator.comparing(AccountDto::code))
				.forEach(child -> add(child, depth + 1, children, rows));
	}

}
