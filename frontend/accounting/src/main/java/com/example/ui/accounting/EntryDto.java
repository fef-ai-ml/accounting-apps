package com.example.ui.accounting;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record EntryDto(Long id, String entryNo, LocalDate date, Long periodId, String reference, String description,
		String status, Long reversalOf, Long reversedBy, String createdByName, String postedByName, Long version,
		BigDecimal totalDebit, List<LineDto> lines, boolean posterBlocked) {

	public String getTotalDebitText() {
		return Idr.format(totalDebit);
	}

}
