package com.example.ui.accounting;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EntryFormTests {

	private static final AccountDto CASH = new AccountDto(11L, "1110", "Kas", "ASSET", 10L, true, true);

	private static final AccountDto SALES = new AccountDto(41L, "4100", "Penjualan", "REVENUE", 40L, true, true);

	private static final LocalDate DATE = LocalDate.of(2026, 3, 15);

	private static BigDecimal money(String value) {
		return new BigDecimal(value);
	}

	private static EntryLine line(AccountDto account, String debit, String credit) {
		EntryLine line = new EntryLine();
		line.setAccount(account);
		line.setDebit(debit == null ? null : money(debit));
		line.setCredit(credit == null ? null : money(credit));
		return line;
	}

	private static EntryForm form(EntryLine... lines) {
		EntryForm form = EntryForm.blank(DATE);
		form.setDescription("Sale");
		form.getLines().clear();
		form.getLines().addAll(List.of(lines));
		return form;
	}

	@Test
	void aBlankFormStartsWithTwoEmptyLines() {
		assertThat(EntryForm.blank(DATE).getLines()).hasSize(2).allMatch(EntryLine::isBlank);
	}

	@Test
	void typingOneSideClearsTheOther() {
		EntryLine line = line(CASH, "100", null);

		line.setCredit(money("5"));
		assertThat(line.getDebit()).isNull();
		assertThat(line.getCredit()).isEqualByComparingTo("5");

		line.setDebit(money("7"));
		assertThat(line.getCredit()).isNull();
	}

	@Test
	void clearingOrZeroingASideLeavesTheOtherAlone() {
		EntryLine line = line(CASH, "100", null);

		line.setCredit(null);
		assertThat(line.getDebit()).isEqualByComparingTo("100");
		line.setCredit(BigDecimal.ZERO);
		assertThat(line.getDebit()).isEqualByComparingTo("100");
		assertThat(line.getCredit()).isNull();
	}

	@Test
	void totalsAndBalanceCountEmptySidesAsZero() {
		EntryForm form = form(line(CASH, "100.50", null), line(SALES, null, "100.5"), new EntryLine());

		assertThat(form.totalDebit()).isEqualByComparingTo("100.50");
		assertThat(form.totalCredit()).isEqualByComparingTo("100.50");
		assertThat(form.difference()).isEqualByComparingTo("0");
		assertThat(form.isBalanced()).isTrue();
	}

	@Test
	void nothingEnteredIsNotBalanced() {
		assertThat(EntryForm.blank(DATE).isBalanced()).isFalse();
	}

	@Test
	void theBalanceButtonPutsTheDifferenceOnTheCurrentLine() {
		EntryLine sales = line(SALES, null, null);
		EntryForm form = form(line(CASH, "100", null), sales);

		form.balance(sales);
		assertThat(sales.getCredit()).isEqualByComparingTo("100");
		assertThat(form.isBalanced()).isTrue();

		EntryLine cash = line(CASH, null, null);
		EntryForm other = form(line(SALES, null, "40"), cash);
		other.balance(cash);
		assertThat(cash.getDebit()).isEqualByComparingTo("40");
	}

	@Test
	void theBalanceButtonReplacesWhatTheCurrentLineHeld() {
		EntryLine sales = line(SALES, "999", null);
		EntryForm form = form(line(CASH, "100", null), sales);

		form.balance(sales);

		assertThat(sales.getDebit()).isNull();
		assertThat(sales.getCredit()).isEqualByComparingTo("100");
	}

	@Test
	void theBalanceButtonClearsTheLineWhenTheOthersAlreadyBalance() {
		EntryLine extra = line(SALES, "5", null);
		EntryForm form = form(line(CASH, "100", null), line(SALES, null, "100"), extra);

		form.balance(extra);

		assertThat(extra.getDebit()).isNull();
		assertThat(extra.getCredit()).isNull();
		assertThat(form.isBalanced()).isTrue();
	}

	@Test
	void textMatchingNoAccountLeavesTheLineWithoutOne() {
		EntryLine line = line(CASH, "100", null);

		assertThat(line.pickByLabel("nothing like this", List.of(CASH, SALES))).isFalse();
		assertThat(line.getAccount()).isNull();
		assertThat(line.getAccountLabel()).isEmpty();

		assertThat(line.pickByLabel("4100 Penjualan", List.of(CASH, SALES))).isTrue();
		assertThat(line.getAccount()).isEqualTo(SALES);
	}

	@Test
	void aNameFindsTheAccountAndAmbiguousTextClearsTheOldOne() {
		EntryLine line = line(CASH, "100", null);

		assertThat(line.pickByLabel("penjualan", List.of(CASH, SALES))).isTrue();
		assertThat(line.getAccount()).isEqualTo(SALES);

		assertThat(line.pickByLabel("1", List.of(CASH, SALES))).isFalse();
		assertThat(line.getAccount()).isNull();
	}

	@Test
	void problemsNameTheLine() {
		assertThat(form(line(CASH, "1", null), line(SALES, null, "1")).problem()).isNull();
		assertThat(form(line(null, "1", null)).problem()).isEqualTo("Line 1: choose an account");
		assertThat(form(new EntryLine(), line(CASH, null, null)).problem()).isEqualTo("Line 2: enter a debit or a credit");

		EntryForm noDescription = form(line(CASH, "1", null));
		noDescription.setDescription("  ");
		assertThat(noDescription.problem()).isEqualTo("Enter a description");

		EntryForm noDate = form(line(CASH, "1", null));
		noDate.setDate(null);
		assertThat(noDate.problem()).isEqualTo("Enter a date");
	}

	@Test
	void anAmountWithMoreThanTwoDecimalsIsAProblem() {
		assertThat(form(new EntryLine(), line(CASH, "100.004", null)).problem()).isEqualTo("Line 2: amounts have at most 2 decimals");
		assertThat(form(line(CASH, null, "0.001")).problem()).isEqualTo("Line 1: amounts have at most 2 decimals");
		assertThat(form(line(CASH, "100.000", null), line(SALES, null, "100.10")).problem()).isNull();
	}

	@Test
	void theRequestDropsBlankLinesAndCarriesTheVersion() {
		EntryForm form = form(new EntryLine(), line(CASH, "1.5", null), new EntryLine());
		form.getLines().get(1).setMemo("note");

		EntryRequest request = form.toRequest(4L);

		assertThat(request.version()).isEqualTo(4L);
		assertThat(request.description()).isEqualTo("Sale");
		assertThat(request.lines()).containsExactly(new LineRequest(11L, money("1.5"), null, "note"));
	}

	@Test
	void anEntryLoadsWithItsAccountsAndZeroSidesEmpty() {
		EntryDto dto = new EntryDto(7L, null, DATE, 3L, "R", "Sale", "DRAFT", null, null, "maker", null, 2L, money("5.00"),
				List.of(new LineDto(1, 11L, money("5.00"), money("0.00"), "m"), new LineDto(2, 41L, money("0.00"), money("5.00"), null)),
				false, null);

		EntryForm form = EntryForm.from(dto, Map.of(11L, CASH, 41L, SALES));

		assertThat(form.getReference()).isEqualTo("R");
		assertThat(form.getLines()).hasSize(2);
		assertThat(form.getLines().get(0).getAccount()).isEqualTo(CASH);
		assertThat(form.getLines().get(0).getCredit()).isNull();
		assertThat(form.getLines().get(1).getCredit()).isEqualByComparingTo("5");
		assertThat(form.isBalanced()).isTrue();
	}

	@Test
	void theSnapshotChangesWithAnyEditAndIgnoresAmountScale() {
		EntryForm form = form(line(CASH, "5", null), line(SALES, null, "5"));
		String saved = form.snapshot();

		assertThat(form(line(CASH, "5.00", null), line(SALES, null, "5.0")).snapshot()).isEqualTo(saved);

		form.getLines().get(0).setDebit(money("6"));
		assertThat(form.snapshot()).isNotEqualTo(saved);
		form.getLines().get(0).setDebit(money("5"));
		assertThat(form.snapshot()).isEqualTo(saved);

		form.setReference("x");
		assertThat(form.snapshot()).isNotEqualTo(saved);
		form.setReference(null);

		form.getLines().get(1).setMemo("x");
		assertThat(form.snapshot()).isNotEqualTo(saved);
	}

}
