package com.example.ui.accounting;

import java.time.LocalDate;
import java.util.List;

/** {@code version} is the one on screen when updating, null when creating. */
public record EntryRequest(LocalDate date, String reference, String description, List<LineRequest> lines,
		Long version) {
}
