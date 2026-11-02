package com.example.accounting;

import java.time.Instant;
import java.time.LocalDate;

public record PeriodResponse(Long id, int year, int month, LocalDate startDate, LocalDate endDate, PeriodStatus status,
		String closedByName, Instant closedAt) {

	static PeriodResponse from(FiscalPeriod period) {
		return new PeriodResponse(period.getId(), period.getYear(), period.getMonth(), period.getStartDate(),
				period.getEndDate(), period.getStatus(), period.getClosedByName(), period.getClosedAt());
	}

}
