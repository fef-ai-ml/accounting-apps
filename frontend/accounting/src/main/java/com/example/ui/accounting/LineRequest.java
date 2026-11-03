package com.example.ui.accounting;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonFormat;

public record LineRequest(Long accountId,
		@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal debit,
		@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal credit,
		String memo) {
}
