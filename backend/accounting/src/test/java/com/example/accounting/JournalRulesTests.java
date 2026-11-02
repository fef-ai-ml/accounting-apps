package com.example.accounting;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JournalRulesTests {

	private static Side side(String debit, String credit) {
		return new Side(new BigDecimal(debit), new BigDecimal(credit));
	}

	private static String codeOf(Runnable rule) {
		try {
			rule.run();
			return null;
		}
		catch (AccountingException ex) {
			return ex.code();
		}
	}

	@Test
	void amountsAreNormalizedToTwoDecimals() {
		assertThat(JournalRules.amount(new BigDecimal("1.5"))).isEqualByComparingTo("1.50").hasScaleOf(2);
		assertThat(JournalRules.amount(new BigDecimal("1.500"))).hasToString("1.50");
		assertThat(JournalRules.amount(new BigDecimal("1E+3"))).hasToString("1000.00");
		assertThat(JournalRules.amount(null)).hasToString("0.00");
	}

	@Test
	void moreThanTwoDecimalsIsNeverRoundedSilently() {
		assertThat(codeOf(() -> JournalRules.amount(new BigDecimal("1.555")))).isEqualTo("invalid-amount");
		assertThat(codeOf(() -> JournalRules.amount(new BigDecimal("0.001")))).isEqualTo("invalid-amount");
	}

	@Test
	void amountsThatDoNotFitAreRefusedWithoutExpandingThem() {
		assertThat(codeOf(() -> JournalRules.amount(new BigDecimal("1E+1000000000")))).isEqualTo("invalid-amount");
		assertThat(codeOf(() -> JournalRules.amount(new BigDecimal("1E-1000000000")))).isEqualTo("invalid-amount");
		assertThat(codeOf(() -> JournalRules.amount(new BigDecimal("123456789012345678")))).isEqualTo("invalid-amount");
		assertThat(JournalRules.amount(new BigDecimal("12345678901234567.89"))).hasToString("12345678901234567.89");
	}

	@Test
	void aLineHasExactlyOnePositiveSide() {
		assertThat(codeOf(() -> JournalRules.checkLine(side("0", "0")))).isEqualTo("invalid-line");
		assertThat(codeOf(() -> JournalRules.checkLine(side("1", "1")))).isEqualTo("invalid-line");
		assertThat(codeOf(() -> JournalRules.checkLine(side("-1", "0")))).isEqualTo("invalid-line");
		assertThat(codeOf(() -> JournalRules.checkLine(side("0", "-1")))).isEqualTo("invalid-line");
		assertThat(codeOf(() -> JournalRules.checkLine(side("1", "0")))).isNull();
		assertThat(codeOf(() -> JournalRules.checkLine(side("0", "1")))).isNull();
	}

	@Test
	void balanceNeedsTwoLinesAndEqualPositiveSides() {
		assertThat(codeOf(() -> JournalRules.checkBalanced(List.of(side("1", "0"))))).isEqualTo("too-few-lines");
		assertThat(codeOf(() -> JournalRules.checkBalanced(List.of(side("2", "0"), side("0", "1")))))
				.isEqualTo("unbalanced");
		assertThat(codeOf(() -> JournalRules.checkBalanced(List.of(side("1.50", "0"), side("0", "1.5"))))).isNull();
		assertThat(codeOf(() -> JournalRules.checkBalanced(List.of(side("1", "0"), side("1", "0"), side("0", "2")))))
				.isNull();
	}

	@Test
	void reversalSwapsSides() {
		assertThat(side("5", "0").swapped()).isEqualTo(side("0", "5"));
	}

	@Test
	void makerCheckerMatrix() {
		assertThat(JournalRules.mayPost("poster", "maker", "editor", true)).isTrue();
		assertThat(JournalRules.mayPost("maker", "maker", "editor", true)).isFalse();
		assertThat(JournalRules.mayPost("editor", "maker", "editor", true)).isFalse();
		assertThat(JournalRules.mayPost("maker", "maker", "maker", false)).isTrue();
	}

}
