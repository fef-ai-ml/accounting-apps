package com.example.ui.accounting;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AccountMatcherTests {

	private static final AccountDto CASH = new AccountDto(11L, "1110", "Kas Kecil", "ASSET", 10L, true, true);

	private static final AccountDto BANK = new AccountDto(12L, "1120", "Bank", "ASSET", 10L, true, true);

	private final AccountMatcher matcher = new AccountMatcher();

	@Test
	void matchesByCodeOrNameAnywhereIgnoringCase() {
		assertThat(matcher.compare("1110", CASH)).isZero();
		assertThat(matcher.compare("11", CASH)).isZero();
		assertThat(matcher.compare("kecil", CASH)).isZero();
		assertThat(matcher.compare(" KAS ", CASH)).isZero();
		assertThat(matcher.compare("1110 Kas Kecil", CASH)).isZero();
		assertThat(matcher.compare("", CASH)).isZero();
		assertThat(matcher.compare(null, CASH)).isZero();
	}

	@Test
	void anythingElseDoesNotMatch() {
		assertThat(matcher.compare("bank", CASH)).isNotZero();
	}

	@Test
	void findTakesASingleMatchByCodeOrName() {
		assertThat(AccountMatcher.find("kas", List.of(CASH, BANK))).contains(CASH);
		assertThat(AccountMatcher.find(" BANK ", List.of(CASH, BANK))).contains(BANK);
		assertThat(AccountMatcher.find("1110 kas kecil", List.of(CASH, BANK))).contains(CASH);
	}

	@Test
	void findRefusesSeveralMatchesAndBlankText() {
		assertThat(AccountMatcher.find("11", List.of(CASH, BANK))).isEmpty();
		assertThat(AccountMatcher.find("", List.of(CASH, BANK))).isEmpty();
		assertThat(AccountMatcher.find("  ", List.of(CASH, BANK))).isEmpty();
		assertThat(AccountMatcher.find(null, List.of(CASH, BANK))).isEmpty();
		assertThat(AccountMatcher.find("nothing", List.of(CASH, BANK))).isEmpty();
	}

	@Test
	void anExactLabelWinsOverAContainsMatch() {
		AccountDto shortLabel = new AccountDto(14L, "1110", "Kas", "ASSET", 10L, true, true);

		assertThat(AccountMatcher.find("1110 Kas", List.of(CASH, shortLabel))).contains(shortLabel);
	}

}
