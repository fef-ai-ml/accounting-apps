package com.example.ui.accounting;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AccountTreeTests {

	private static AccountDto account(long id, String code, Long parent) {
		return new AccountDto(id, code, "N" + code, "ASSET", parent, parent != null, true);
	}

	private static List<String> codes(List<AccountRow> rows) {
		return rows.stream().map(row -> row.getAccount().code() + ":" + row.depth()).toList();
	}

	@Test
	void childrenFollowTheirParentInCodeOrder() {
		List<AccountRow> rows = AccountTree.rows(List.of(account(4, "2000", null), account(3, "1120", 2L),
				account(5, "1110", 2L), account(2, "1100", 1L), account(1, "1000", null), account(6, "2100", 4L)));

		assertThat(codes(rows)).containsExactly("1000:0", "1100:1", "1110:2", "1120:2", "2000:0", "2100:1");
	}

	@Test
	void anAccountWhoseParentIsMissingIsStillListed() {
		assertThat(codes(AccountTree.rows(List.of(account(1, "1000", null), account(9, "9000", 99L)))))
				.containsExactly("1000:0", "9000:0");
	}

	@Test
	void indentationGrowsWithDepth() {
		assertThat(new AccountRow(account(1, "1", null), 0).getIndentStyle()).isEqualTo("padding-left: 0px");
		assertThat(new AccountRow(account(1, "1", null), 2).getIndentStyle()).isEqualTo("padding-left: 40px");
	}

}
