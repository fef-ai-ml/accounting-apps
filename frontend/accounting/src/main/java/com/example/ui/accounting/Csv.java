package com.example.ui.accounting;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Report exports that open safely and correctly in a spreadsheet. */
final class Csv {

	private static final String BOM = "﻿";

	private static final String RISKY_START = "=+-@\t\r";

	private Csv() {
	}

	static byte[] trialBalance(TrialBalanceDto report) {
		List<String> rows = new ArrayList<>();
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
		rows.add("Date,Entry,Description,Memo,Debit,Credit,Balance");
		rows.add(line("Opening balance", "", "", "", "", "", amount(report.openingBalance())));
		for (GeneralLedgerDto.Line row : report.lines()) {
			rows.add(line(row.getDateText(), text(row.entryNo()), text(row.description()), text(row.memo()), amount(row.debit()),
					amount(row.credit()), amount(row.runningBalance())));
		}
		rows.add(line("Closing balance", "", "", "", "", "", amount(report.closingBalance())));
		return bytes(rows);
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
		String safe = RISKY_START.indexOf(value.charAt(0)) >= 0 ? "'" + value : value;
		return safe.contains(",") || safe.contains("\"") || safe.contains("\n") || safe.contains("\r")
				? "\"" + safe.replace("\"", "\"\"") + "\""
				: safe;
	}

}
