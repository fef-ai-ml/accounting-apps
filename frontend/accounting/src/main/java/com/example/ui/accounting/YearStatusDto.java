package com.example.ui.accounting;

public record YearStatusDto(int year, boolean closed, Long entryId, String entryNo) {
}
