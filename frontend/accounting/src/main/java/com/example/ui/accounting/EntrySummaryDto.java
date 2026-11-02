package com.example.ui.accounting;

import java.math.BigDecimal;
import java.time.LocalDate;

public record EntrySummaryDto(Long id, String entryNo, LocalDate date, String reference, String description,
		String status, BigDecimal totalDebit) {

	public Long getId() {
		return id;
	}

	public String getEntryNo() {
		return entryNo;
	}

	public String getDateText() {
		return date == null ? "" : date.toString();
	}

	public String getReference() {
		return reference;
	}

	public String getDescription() {
		return description;
	}

	public String getStatus() {
		return status;
	}

	public String getTotalDebitText() {
		return Idr.format(totalDebit);
	}

}
