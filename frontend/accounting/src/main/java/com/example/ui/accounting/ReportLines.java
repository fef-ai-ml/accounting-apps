package com.example.ui.accounting;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** Flattens a statement into the rows the screen and the CSV both show. */
final class ReportLines {

	private ReportLines() {
	}

	static List<ReportLine> of(IncomeStatementDto report) {
		List<ReportLine> lines = new ArrayList<>();
		section(lines, "Revenue", report.revenue());
		total(lines, "Revenue", "Total revenue", report.totalRevenue());
		section(lines, "Expenses", report.expenses());
		total(lines, "Expenses", "Total expenses", report.totalExpenses());
		total(lines, "Net", report.netProfit().signum() < 0 ? "Net loss" : "Net profit", report.netProfit().abs());
		return lines;
	}

	static List<ReportLine> of(BalanceSheetDto report) {
		List<ReportLine> lines = new ArrayList<>();
		section(lines, "Assets", report.assets());
		total(lines, "Assets", "Total assets", report.totalAssets());
		section(lines, "Liabilities", report.liabilities());
		total(lines, "Liabilities", "Total liabilities", report.totalLiabilities());
		section(lines, "Equity", report.equity());
		lines.add(new ReportLine("Equity", "", "Earnings not yet closed", 0, report.earningsNotYetClosed(), false));
		total(lines, "Equity", "Total equity", report.totalEquity());
		total(lines, "Total", "Total liabilities and equity", report.totalLiabilitiesAndEquity());
		total(lines, "Total", "Difference", report.difference());
		return lines;
	}

	private static void section(List<ReportLine> lines, String title, List<StatementRowDto> rows) {
		lines.add(new ReportLine(title, "", title, 0, null, false));
		rows.forEach(row -> lines.add(new ReportLine(title, row.code(), row.name(), row.depth(), row.amount(), false)));
	}

	private static void total(List<ReportLine> lines, String section, String name, BigDecimal amount) {
		lines.add(new ReportLine(section, "", name, 0, amount, true));
	}

}
