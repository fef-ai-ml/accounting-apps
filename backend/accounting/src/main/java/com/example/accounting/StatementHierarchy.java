package com.example.accounting;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Turns one section's accounts and balances into rows, each parent before its children. */
final class StatementHierarchy {

	private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

	record Node(Long id, String code, String name, Long parentId) {
	}

	private StatementHierarchy() {
	}

	/** A parent outside this section makes the account top-level. Rows that net to zero with no movement below are hidden. */
	static List<StatementRow> rows(List<Node> nodes, Map<Long, BigDecimal> balances) {
		Set<Long> section = nodes.stream().map(Node::id).collect(Collectors.toSet());
		Map<Long, List<Node>> children = new HashMap<>();
		List<Node> roots = new ArrayList<>();
		nodes.stream().sorted(Comparator.comparing(Node::code)).forEach(node -> {
			if (node.parentId() != null && section.contains(node.parentId())) {
				children.computeIfAbsent(node.parentId(), parent -> new ArrayList<>()).add(node);
			}
			else {
				roots.add(node);
			}
		});
		List<StatementRow> rows = new ArrayList<>();
		roots.forEach(root -> add(root, 0, children, balances, rows));
		return rows;
	}

	private static BigDecimal add(Node node, int depth, Map<Long, List<Node>> children, Map<Long, BigDecimal> balances,
			List<StatementRow> rows) {
		int at = rows.size();
		BigDecimal own = balances.getOrDefault(node.id(), ZERO);
		BigDecimal amount = own;
		for (Node child : children.getOrDefault(node.id(), List.of())) {
			amount = amount.add(add(child, depth + 1, children, balances, rows));
		}
		// A descendant added a row only if something moved there, so the row count says whether to show this one.
		if (own.signum() != 0 || rows.size() > at) {
			rows.add(at, new StatementRow(node.id(), node.code(), node.name(), depth, amount));
		}
		return amount;
	}

}
