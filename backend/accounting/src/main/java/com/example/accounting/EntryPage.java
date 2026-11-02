package com.example.accounting;

import java.util.List;

public record EntryPage(List<EntrySummary> items, long total, int page, int size) {
}
