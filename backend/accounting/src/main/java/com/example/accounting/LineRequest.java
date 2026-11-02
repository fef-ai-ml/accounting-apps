package com.example.accounting;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record LineRequest(@NotNull Long accountId, BigDecimal debit, BigDecimal credit, @Size(max = 200) String memo) {
}
