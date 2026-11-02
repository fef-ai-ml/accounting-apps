package com.example.accounting;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.fasterxml.jackson.annotation.JsonFormat;

public record EntrySummary(Long id, String entryNo, LocalDate date, String reference, String description,
		EntryStatus status, @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal totalDebit) {

	static EntrySummary from(JournalEntry entry) {
		return new EntrySummary(entry.getId(), entry.getEntryNo(), entry.getEntryDate(), entry.getReference(),
				entry.getDescription(), entry.getStatus(), entry.totalDebit());
	}

}
