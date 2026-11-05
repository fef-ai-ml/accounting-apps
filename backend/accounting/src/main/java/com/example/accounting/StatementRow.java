package com.example.accounting;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonFormat;

/** {@code amount} is on the account's normal side and includes its descendants, so a contra-asset shows as negative. */
public record StatementRow(Long accountId, String code, String name, int depth,
		@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount) {
}
