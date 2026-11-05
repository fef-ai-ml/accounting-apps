package com.example.ui.accounting;

import java.math.BigDecimal;

public record StatementRowDto(Long accountId, String code, String name, int depth, BigDecimal amount) {
}
