package com.example.ui.accounting;

import java.util.List;

public record EntryPageDto(List<EntrySummaryDto> items, long total, int page, int size) {
}
