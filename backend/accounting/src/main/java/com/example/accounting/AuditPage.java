package com.example.accounting;

import java.util.List;

public record AuditPage(List<AuditRow> items, long total, int page, int size) {
}
