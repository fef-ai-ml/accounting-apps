package com.example.ui.accounting;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AccountMatcherTests {

	private static final AccountDto CASH = new AccountDto(11L, "1110", "Kas Kecil", "ASSET", 10L, true, true);

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

}
