package com.example.ui.accounting;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** {@code netProfit} is negative for a loss. */
public record IncomeStatementDto(LocalDate from, LocalDate to, List<StatementRowDto> revenue, List<StatementRowDto> expenses,
		BigDecimal totalRevenue, BigDecimal totalExpenses, BigDecimal netProfit) {
}
