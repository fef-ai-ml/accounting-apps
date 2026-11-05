package com.example.accounting;

/** {@code entryId} and {@code entryNo} name the active close, and are null when the year has none. */
public record YearStatus(int year, boolean closed, Long entryId, String entryNo) {
}
