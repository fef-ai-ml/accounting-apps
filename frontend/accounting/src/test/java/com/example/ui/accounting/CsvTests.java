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
		assertThat(csv.substring(1).split("\r\n")).containsExactly("Trial balance as of,2026-03-31",
				"Code,Name,Type,Debit,Credit,Balance", "1290,Akumulasi,ASSET,0.00,5.00,-5.00", "Total,,,0.00,5.00,0.00");
	}

	@Test
	void textThatLooksLikeAFormulaCannotRun() {
		for (String risky : new String[] { "=HYPERLINK(1)", "+1", "-1", "@x", "\tx", "\rx", "＝1", "＋1", "－1", "＠x" }) {
			String csv = text(Csv.trialBalance(trialBalance(risky, "1.00")));

			assertThat(csv).as(risky).contains("'" + risky.charAt(0));
		}
	}

	@Test
	void aFormulaBehindInvisibleCharactersCannotRunEither() {
		for (String risky : new String[] { " =1", "\n=1", " =1", "﻿=1", "​=1", "⁠ +1", "　@x" }) {
			String csv = text(Csv.trialBalance(trialBalance(risky, "1.00")));

			assertThat(csv).as(risky).contains("'" + risky);
		}
	}

	@Test
	void textThatOnlyContainsARiskyCharacterStaysAsItIs() {
		assertThat(text(Csv.trialBalance(trialBalance(" Kas", "1.00")))).contains("1290, Kas,ASSET").doesNotContain("'");
		assertThat(text(Csv.trialBalance(trialBalance("Kas=1", "1.00")))).contains("1290,Kas=1,ASSET");
	}

	@Test
	void everyLedgerTextCellIsGuarded() {
		String csv = text(Csv.generalLedger(ledger("1110", "Kas", new GeneralLedgerDto.Line(LocalDate.of(2026, 2, 5), 9L, "+no", " =desc",
				"@memo", money("40.00"), null, money("140.00")))));

		assertThat(csv).contains("2026-02-05,'+no,' =desc,'@memo,40.00,,140.00");
	}

	@Test
	void anAccountCodeStartingLikeAFormulaIsGuardedInTheFirstRow() {
		assertThat(text(Csv.generalLedger(ledger("=1", "Kas")))).contains("General ledger,'=1 Kas,2026-02-01,2026-02-28");
	}

	@Test
	void aSemicolonOrTabIsQuotedSoAnotherListSeparatorCannotSplitTheCell() {
		assertThat(text(Csv.trialBalance(trialBalance("x;=1+1", "1.00")))).contains(",\"x;=1+1\",");
		assertThat(text(Csv.trialBalance(trialBalance("x\t=1+1", "1.00")))).contains(",\"x\t=1+1\",");
	}

	@Test
	void negativeAmountsStayNumeric() {
		assertThat(text(Csv.trialBalance(trialBalance("x", "-5.00")))).contains(",-5.00").doesNotContain("'-");
	}

	@Test
	void separatorsAndQuotesAreEscaped() {
		assertThat(text(Csv.trialBalance(trialBalance("a,b\"c\nd", "1.00")))).contains("\"a,b\"\"c\nd\"");
	}

	private static GeneralLedgerDto ledger(String code, String name, GeneralLedgerDto.Line... lines) {
		return new GeneralLedgerDto(1L, code, name, "ASSET", LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28), money("100.00"),
				List.of(lines), money("140.00"));
	}

	@Test
	void generalLedgerNamesItsAccountAndHasOpeningLinesAndClosing() {
		GeneralLedgerDto ledger = ledger("1110", "Kas Kecil", new GeneralLedgerDto.Line(LocalDate.of(2026, 2, 5), 9L, "JE-2026-000002",
				"Sale", null, money("40.00"), money("0.00"), money("140.00")));

		assertThat(text(Csv.generalLedger(ledger)).substring(1).split("\r\n")).containsExactly(
				"General ledger,1110 Kas Kecil,2026-02-01,2026-02-28", "Date,Entry,Description,Memo,Debit,Credit,Balance",
				"Opening balance,,,,,,100.00", "2026-02-05,JE-2026-000002,Sale,,40.00,0.00,140.00", "Closing balance,,,,,,140.00");
	}

	@Test
	void theLedgerFileNameKeepsOnlySafeCharacters() {
		assertThat(Csv.generalLedgerFileName(ledger("1110", "Kas"))).isEqualTo("general-ledger-1110-2026-02-01-2026-02-28.csv");
		assertThat(Csv.generalLedgerFileName(ledger("1/10 a\\b\"c<d", "Kas"))).isEqualTo("general-ledger-1_10_a_b_c_d-2026-02-01-2026-02-28.csv");
		assertThat(Csv.generalLedgerFileName(ledger("1.1_a-B", "Kas"))).isEqualTo("general-ledger-1.1_a-B-2026-02-01-2026-02-28.csv");
	}

	@Test
	void theIncomeStatementListsEachAmountWithItsSectionAndLevel() {
		assertThat(text(Csv.incomeStatement(ReportLinesTests.income("700.00"))).substring(1).split("\r\n")).containsExactly(
				"Income statement,2026-01-01,2026-12-31", "Section,Code,Name,Level,Amount",
				"Revenue,4000,Pendapatan,0,1000.00", "Revenue,4100,Penjualan,1,1000.00", "Revenue,,Total revenue,,1000.00",
				"Expenses,6120,Beban Sewa,0,300.00", "Expenses,,Total expenses,,300.00", "Net,,Net profit,,700.00");
	}

	@Test
	void theBalanceSheetExportsAContraAccountAsNegativeAndTheDifference() {
		String csv = text(Csv.balanceSheet(ReportLinesTests.balance()));

		assertThat(csv).contains("Balance sheet as of,2026-06-30", "Assets,1290,Akumulasi,0,-5.00", "Equity,,Earnings not yet closed,,-15.00",
				"Total,,Difference,,0.00");
	}

	@Test
	void aStatementRowThatLooksLikeAFormulaCannotRun() {
		IncomeStatementDto report = new IncomeStatementDto(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 2),
				List.of(new StatementRowDto(1L, "=1", "=2", 0, money("1.00"))), List.of(), money("1.00"), money("0.00"), money("1.00"));

		assertThat(text(Csv.incomeStatement(report))).contains("'=1,'=2");
	}

}
