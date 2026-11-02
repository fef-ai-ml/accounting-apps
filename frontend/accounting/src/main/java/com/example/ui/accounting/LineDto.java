package com.example.ui.accounting;

import java.math.BigDecimal;

public record LineDto(int lineNo, Long accountId, BigDecimal debit, BigDecimal credit, String memo) {
}
