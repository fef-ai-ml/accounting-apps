package com.example.accounting;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonFormat;

/** {@code earningsNotYetClosed} is calculated and is not an account; {@code totalEquity} includes it. */
public record BalanceSheet(LocalDate asOf, List<StatementRow> assets, List<StatementRow> liabilities,
		List<StatementRow> equity,
		@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal earningsNotYetClosed,
		@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal totalAssets,
		@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal totalLiabilities,
		@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal totalEquity,
		@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal totalLiabilitiesAndEquity,
		@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal difference) {
}
