package com.example.ui.accounting;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record BalanceSheetDto(LocalDate asOf, List<StatementRowDto> assets, List<StatementRowDto> liabilities,
		List<StatementRowDto> equity, BigDecimal earningsNotYetClosed, BigDecimal totalAssets, BigDecimal totalLiabilities,
		BigDecimal totalEquity, BigDecimal totalLiabilitiesAndEquity, BigDecimal difference) {
}
