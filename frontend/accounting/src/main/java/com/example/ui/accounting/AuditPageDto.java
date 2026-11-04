package com.example.ui.accounting;

import java.util.List;

public record AuditPageDto(List<AuditRowDto> items, long total, int page, int size) {
}
