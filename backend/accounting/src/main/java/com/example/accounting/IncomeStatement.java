package com.example.accounting;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonFormat;

/** {@code netProfit} is negative for a loss. */
public record IncomeStatement(LocalDate from, LocalDate to, List<StatementRow> revenue, List<StatementRow> expenses,
		@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal totalRevenue,
		@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal totalExpenses,
		@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal netProfit) {
}
