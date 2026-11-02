package com.example.accounting;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonFormat;

public record TrialBalance(LocalDate asOf, List<Row> rows,
		@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal totalDebit,
		@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal totalCredit,
		@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal difference) {

	/** {@code balance} is on the account's normal side, so a contra-asset shows as a negative asset. */
	public record Row(Long accountId, String code, String name, AccountType type,
			@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal debit,
			@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal credit,
			@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal balance) {
	}

}
