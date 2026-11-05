package com.example.ui.accounting;

import java.math.BigDecimal;

public record YearCloseResultDto(int year, Long entryId, String entryNo, BigDecimal netProfit) {
}
