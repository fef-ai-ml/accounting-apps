package com.example.ui.accounting;

import java.time.LocalDate;
import java.time.Month;
import java.time.format.TextStyle;
import java.util.Locale;

public record PeriodDto(Long id, int year, int month, LocalDate startDate, LocalDate endDate, String status,
		String closedByName) {

	public Long getId() {
		return id;
	}

	public String getLabel() {
		return Month.of(month).getDisplayName(TextStyle.SHORT, Locale.ENGLISH) + " " + year;
	}

	public String getStatus() {
		return status;
	}

	public String getClosedByName() {
		return closedByName;
	}

	public boolean isOpen() {
		return "OPEN".equals(status);
	}

}
