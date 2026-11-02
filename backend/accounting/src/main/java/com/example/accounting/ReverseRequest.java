package com.example.accounting;

import java.time.LocalDate;

/** {@code date} defaults to today; {@code copyToDraft} also creates a draft with the original's lines. */
public record ReverseRequest(LocalDate date, Boolean copyToDraft) {
}
