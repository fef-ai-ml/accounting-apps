package com.example.ui.accounting;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CsvTests {

	private static BigDecimal money(String value) {
		return new BigDecimal(value);
	}

	private static String text(byte[] csv) {
		return new String(csv, StandardCharsets.UTF_8);
	}

	private static TrialBalanceDto trialBalance(String name, String balance) {
		return new TrialBalanceDto(LocalDate.of(2026, 3, 31),
				List.of(new TrialBalanceDto.Row(1L, "1290", name, "ASSET", money("0.00"), money("5.00"), money(balance))),
				money("0.00"), money("5.00"), money("0.00"));
	}

	@Test
	void trialBalanceHasAHeaderRowsAndTotals() {
		String csv = text(Csv.trialBalance(trialBalance("Akumulasi", "-5.00")));

		assertThat(csv).startsWith("﻿");
		assertThat(csv.substring(1).split("\r\n")).containsExactly("Code,Name,Type,Debit,Credit,Balance",
				"1290,Akumulasi,ASSET,0.00,5.00,-5.00", "Total,,,0.00,5.00,0.00");
	}

	@Test
	void textThatLooksLikeAFormulaCannotRun() {
		for (String risky : new String[] { "=HYPERLINK(1)", "+1", "-1", "@x", "\tx", "\rx" }) {
			String csv = text(Csv.trialBalance(trialBalance(risky, "1.00")));

			assertThat(csv).as(risky).contains("'" + risky.charAt(0));
		}
	}

	@Test
	void negativeAmountsStayNumeric() {
		assertThat(text(Csv.trialBalance(trialBalance("x", "-5.00")))).contains(",-5.00").doesNotContain("'-");
	}

	@Test
	void separatorsAndQuotesAreEscaped() {
		assertThat(text(Csv.trialBalance(trialBalance("a,b\"c\nd", "1.00")))).contains("\"a,b\"\"c\nd\"");
	}

	@Test
	void generalLedgerHasOpeningLinesAndClosing() {
		GeneralLedgerDto ledger = new GeneralLedgerDto(1L, "1110", "Kas", "ASSET", LocalDate.of(2026, 2, 1),
				LocalDate.of(2026, 2, 28), money("100.00"),
				List.of(new GeneralLedgerDto.Line(LocalDate.of(2026, 2, 5), 9L, "JE-2026-000002", "Sale", null, money("40.00"),
						money("0.00"), money("140.00"))),
				money("140.00"));

		assertThat(text(Csv.generalLedger(ledger)).substring(1).split("\r\n")).containsExactly(
				"Date,Entry,Description,Memo,Debit,Credit,Balance", "Opening balance,,,,,,100.00",
				"2026-02-05,JE-2026-000002,Sale,,40.00,0.00,140.00", "Closing balance,,,,,,140.00");
	}

}
