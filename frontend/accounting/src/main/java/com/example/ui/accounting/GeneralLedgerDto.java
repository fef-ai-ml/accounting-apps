package com.example.ui.accounting;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record GeneralLedgerDto(Long accountId, String accountCode, String accountName, String type, LocalDate from,
		LocalDate to, BigDecimal openingBalance, List<Line> lines, BigDecimal closingBalance) {

	public record Line(LocalDate date, Long entryId, String entryNo, String description, String memo, BigDecimal debit,
			BigDecimal credit, BigDecimal runningBalance) {

		public String getDateText() {
			return date == null ? "" : date.toString();
		}

		public String getEntryNo() {
			return entryNo;
		}

		public String getDescription() {
			return description;
		}

		public String getMemo() {
			return memo;
		}

		public String getDebitText() {
			return Idr.format(debit);
		}

		public String getCreditText() {
			return Idr.format(credit);
		}

		public String getRunningBalanceText() {
			return Idr.format(runningBalance);
		}

	}

}
