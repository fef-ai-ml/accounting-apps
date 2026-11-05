package com.example.ui.accounting;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Report exports that open safely and correctly in a spreadsheet. */
final class Csv {

	private static final String BOM = "﻿";

	private static final String RISKY_START = "=+-@\t\r＝＋－＠";

	private static final String NEEDS_QUOTES = ",;\"\t\n\r";

	private Csv() {
	}

	static byte[] trialBalance(TrialBalanceDto report) {
		List<String> rows = new ArrayList<>();
		rows.add(line("Trial balance as of", String.valueOf(report.asOf())));
		rows.add("Code,Name,Type,Debit,Credit,Balance");
		for (TrialBalanceDto.Row row : report.rows()) {
			rows.add(line(text(row.code()), text(row.name()), text(row.type()), amount(row.debit()), amount(row.credit()),
					amount(row.balance())));
		}
		rows.add(line("Total", "", "", amount(report.totalDebit()), amount(report.totalCredit()), amount(report.difference())));
		return bytes(rows);
	}

	static byte[] generalLedger(GeneralLedgerDto report) {
		List<String> rows = new ArrayList<>();
		rows.add(line("General ledger", text(report.accountCode() + " " + report.accountName()), String.valueOf(report.from()),
				String.valueOf(report.to())));
		rows.add("Date,Entry,Description,Memo,Debit,Credit,Balance");
		rows.add(line("Opening balance", "", "", "", "", "", amount(report.openingBalance())));
		for (GeneralLedgerDto.Line row : report.lines()) {
			rows.add(line(row.getDateText(), text(row.entryNo()), text(row.description()), text(row.memo()), amount(row.debit()),
					amount(row.credit()), amount(row.runningBalance())));
		}
		rows.add(line("Closing balance", "", "", "", "", "", amount(report.closingBalance())));
		return bytes(rows);
	}

	static byte[] incomeStatement(IncomeStatementDto report) {
		return statement(line("Income statement", String.valueOf(report.from()), String.valueOf(report.to())), ReportLines.of(report));
	}

	static byte[] balanceSheet(BalanceSheetDto report) {
		return statement(line("Balance sheet as of", String.valueOf(report.asOf())), ReportLines.of(report));
	}

	/** A parent's amount includes its children, so Level lets a spreadsheet user sum one level only. */
	private static byte[] statement(String title, List<ReportLine> lines) {
		List<String> rows = new ArrayList<>();
		rows.add(title);
		rows.add("Section,Code,Name,Level,Amount");
		for (ReportLine row : lines) {
			if (row.amount() != null) {
				rows.add(line(row.section(), text(row.code()), text(row.name()), row.code().isEmpty() ? "" : String.valueOf(row.depth()),
						amount(row.amount())));
			}
		}
		return bytes(rows);
	}

	/** An account code is free text; a slash in it would turn the download URL into a 400. */
	static String generalLedgerFileName(GeneralLedgerDto report) {
		return ("general-ledger-" + report.accountCode() + "-" + report.from() + "-" + report.to() + ".csv")
				.replaceAll("[^A-Za-z0-9._-]", "_");
	}

	private static byte[] bytes(List<String> rows) {
		return (BOM + String.join("\r\n", rows) + "\r\n").getBytes(StandardCharsets.UTF_8);
	}

	private static String line(String... cells) {
		return String.join(",", cells);
	}

	private static String amount(BigDecimal value) {
		return value == null ? "" : value.toPlainString();
	}

	/** A cell a spreadsheet would run as a formula gets a leading apostrophe; separators and quotes are escaped. */
	private static String text(String value) {
		if (value == null || value.isEmpty()) {
			return "";
		}
		String safe = startsRisky(value) ? "'" + value : value;
		return safe.chars().anyMatch(c -> NEEDS_QUOTES.indexOf(c) >= 0) ? "\"" + safe.replace("\"", "\"\"") + "\"" : safe;
	}

	/** Spreadsheets skip what cannot be seen before deciding a cell is a formula, so the check must too. */
	private static boolean startsRisky(String value) {
		for (char c : value.toCharArray()) {
			if (RISKY_START.indexOf(c) >= 0) {
				return true;
			}
			if (!Character.isWhitespace(c) && !Character.isSpaceChar(c) && Character.getType(c) != Character.FORMAT) {
				return false;
			}
		}
		return false;
	}

}
