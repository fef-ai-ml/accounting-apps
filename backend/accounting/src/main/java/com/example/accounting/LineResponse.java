package com.example.accounting;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonFormat;

public record LineResponse(int lineNo, Long accountId,
		@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal debit,
		@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal credit,
		String memo) {

	static LineResponse from(JournalLine line) {
		return new LineResponse(line.getLineNo(), line.getAccountId(), line.getDebit(), line.getCredit(), line.getMemo());
	}

}
