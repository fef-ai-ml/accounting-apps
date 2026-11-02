package com.example.accounting;

import java.time.LocalDate;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** {@code version} is required when updating, ignored when creating. */
public record EntryRequest(
		@NotNull LocalDate date,
		@Size(max = 100) String reference,
		@NotBlank @Size(max = 500) String description,
		@NotNull List<@Valid LineRequest> lines,
		Long version) {
}
