package com.example.accounting;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonFormat;

/** {@code netProfit} is negative for a loss. */
public record YearCloseResponse(int year, Long entryId, String entryNo,
		@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal netProfit) {
}
