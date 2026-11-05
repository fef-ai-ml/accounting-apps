package com.example.accounting;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.example.accounting.StatementHierarchy.Node;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class StatementHierarchyTests {

	private static final Node HEADER = new Node(1L, "1000", "Aset", null);

	private static final Node CASH = new Node(2L, "1110", "Kas", 1L);

	private static final Node BANK = new Node(3L, "1120", "Bank", 1L);

	private static BigDecimal money(String value) {
		return new BigDecimal(value);
	}

	@Test
	void parentsComeBeforeChildrenSortedByCodeAndIncludeTheirDescendants() {
		List<StatementRow> rows = StatementHierarchy.rows(List.of(BANK, CASH, HEADER),
				Map.of(2L, money("10.00"), 3L, money("5.00")));

		assertThat(rows).extracting(StatementRow::code, StatementRow::depth, StatementRow::amount).containsExactly(
				tuple("1000", 0, money("15.00")), tuple("1110", 1, money("10.00")), tuple("1120", 1, money("5.00")));
	}

	@Test
	void aParentsOwnBalanceCounts() {
		List<StatementRow> rows = StatementHierarchy.rows(List.of(HEADER, CASH), Map.of(1L, money("1.00"), 2L, money("2.00")));

		assertThat(rows.get(0).amount()).isEqualTo(money("3.00"));
	}

	@Test
	void anAccountWhoseParentIsNotInTheSectionIsTopLevel() {
		assertThat(StatementHierarchy.rows(List.of(CASH), Map.of(2L, money("2.00"))))
				.extracting(StatementRow::code, StatementRow::depth).containsExactly(tuple("1110", 0));
	}

	@Test
	void rowsThatNetToZeroWithNoMovementBelowThemAreHidden() {
		assertThat(StatementHierarchy.rows(List.of(HEADER, CASH, BANK), Map.of(2L, money("0.00")))).isEmpty();
	}

	@Test
	void aParentStaysWhenItsChildrenCancelOut() {
		List<StatementRow> rows = StatementHierarchy.rows(List.of(HEADER, CASH, BANK),
				Map.of(2L, money("5.00"), 3L, money("-5.00")));

		assertThat(rows).extracting(StatementRow::code).containsExactly("1000", "1110", "1120");
		assertThat(rows.get(0).amount()).isEqualByComparingTo("0");
	}

}
