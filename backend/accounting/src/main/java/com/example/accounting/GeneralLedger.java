package com.example.accounting;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonFormat;

public record GeneralLedger(Long accountId, String accountCode, String accountName, AccountType type, LocalDate from,
		LocalDate to, @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal openingBalance, List<Line> lines,
		@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal closingBalance) {

	public record Line(LocalDate date, Long entryId, String entryNo, String description, String memo,
			@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal debit,
			@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal credit,
			@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal runningBalance) {
	}

}
