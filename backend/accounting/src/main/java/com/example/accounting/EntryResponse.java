package com.example.accounting;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonFormat;

public record EntryResponse(Long id, String entryNo, LocalDate date, Long periodId, String reference,
		String description, EntryStatus status, Long reversalOf, Long reversedBy, String createdByName,
		Instant createdAt, String lastModifiedByName, Instant lastModifiedAt, String postedByName, Instant postedAt,
		Long version, @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal totalDebit, List<LineResponse> lines,
		boolean posterBlocked) {

	static EntryResponse from(JournalEntry entry) {
		return from(entry, false);
	}

	/** {@code posterBlocked}: maker/checker stops the current user posting this draft, so the screen can say why. */
	static EntryResponse from(JournalEntry entry, boolean posterBlocked) {
		return new EntryResponse(entry.getId(), entry.getEntryNo(), entry.getEntryDate(), entry.getPeriodId(),
				entry.getReference(), entry.getDescription(), entry.getStatus(), entry.getReversalOf(),
				entry.getReversedBy(), entry.getCreatedByName(), entry.getCreatedAt(), entry.getLastModifiedByName(),
				entry.getLastModifiedAt(), entry.getPostedByName(), entry.getPostedAt(), entry.getVersion(),
				entry.totalDebit(), entry.getLines().stream().map(LineResponse::from).toList(), posterBlocked);
	}

}
