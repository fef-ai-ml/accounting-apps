package com.example.ui.accounting;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class ReportLinesTests {

	private static BigDecimal money(String value) {
		return new BigDecimal(value);
	}

	static IncomeStatementDto income(String net) {
		return new IncomeStatementDto(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
				List.of(new StatementRowDto(1L, "4000", "Pendapatan", 0, money("1000.00")),
						new StatementRowDto(2L, "4100", "Penjualan", 1, money("1000.00"))),
				List.of(new StatementRowDto(3L, "6120", "Beban Sewa", 0, money("300.00"))), money("1000.00"), money("300.00"), money(net));
	}

	static BalanceSheetDto balance() {
		return new BalanceSheetDto(LocalDate.of(2026, 6, 30),
				List.of(new StatementRowDto(1L, "1290", "Akumulasi", 0, money("-5.00"))), List.of(),
				List.of(new StatementRowDto(2L, "3100", "Modal", 0, money("10.00"))), money("-15.00"), money("-5.00"), money("0.00"),
				money("-5.00"), money("-5.00"), money("0.00"));
	}

	@Test
	void theIncomeStatementHasSectionsTotalsAndANamedNet() {
		assertThat(ReportLines.of(income("700.00"))).extracting(ReportLine::name, ReportLine::depth, ReportLine::amount).containsExactly(
				tuple("Revenue", 0, null), tuple("Pendapatan", 0, money("1000.00")), tuple("Penjualan", 1, money("1000.00")),
				tuple("Total revenue", 0, money("1000.00")), tuple("Expenses", 0, null), tuple("Beban Sewa", 0, money("300.00")),
				tuple("Total expenses", 0, money("300.00")), tuple("Net profit", 0, money("700.00")));
	}

	@Test
	void aLossIsNamedAndShownAsAPositiveAmount() {
		ReportLine last = ReportLines.of(income("-200.00")).getLast();

		assertThat(last.name()).isEqualTo("Net loss");
		assertThat(last.getAmountText()).isEqualTo("200,00");
	}

	@Test
	void theBalanceSheetEndsEquityWithEarningsAndShowsTheDifference() {
		assertThat(ReportLines.of(balance())).extracting(ReportLine::name, ReportLine::amount).containsExactly(
				tuple("Assets", null), tuple("Akumulasi", money("-5.00")), tuple("Total assets", money("-5.00")),
				tuple("Liabilities", null), tuple("Total liabilities", money("0.00")),
				tuple("Equity", null), tuple("Modal", money("10.00")), tuple("Earnings not yet closed", money("-15.00")),
				tuple("Total equity", money("-5.00")), tuple("Total liabilities and equity", money("-5.00")),
				tuple("Difference", money("0.00")));
	}

	@Test
	void rowsIndentByDepthAndHeadingsAndTotalsAreBold() {
		List<ReportLine> lines = ReportLines.of(income("700.00"));

		assertThat(lines.get(2).getStyle()).isEqualTo("padding-left:16px");
		assertThat(lines.get(0).getStyle()).contains("font-weight:bold");
		assertThat(lines.get(3).getStyle()).contains("font-weight:bold");
		assertThat(lines.get(0).getAmountText()).isEmpty();
	}

}
