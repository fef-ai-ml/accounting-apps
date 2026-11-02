package com.example.ui.accounting;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record TrialBalanceDto(LocalDate asOf, List<Row> rows, BigDecimal totalDebit, BigDecimal totalCredit,
		BigDecimal difference) {

	public record Row(Long accountId, String code, String name, String type, BigDecimal debit, BigDecimal credit,
			BigDecimal balance) {

		public String getCode() {
			return code;
		}

		public String getName() {
			return name;
		}

		public String getDebitText() {
			return Idr.format(debit);
		}

		public String getCreditText() {
			return Idr.format(credit);
		}

		public String getBalanceText() {
			return Idr.format(balance);
		}

	}

}
