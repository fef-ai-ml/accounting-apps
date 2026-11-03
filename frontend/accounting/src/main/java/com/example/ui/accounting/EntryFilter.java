package com.example.ui.accounting;

import java.time.LocalDate;

/** Null or blank values are not sent. */
public record EntryFilter(LocalDate from, LocalDate to, String status, Long accountId, String q, int page, int size) {
}
