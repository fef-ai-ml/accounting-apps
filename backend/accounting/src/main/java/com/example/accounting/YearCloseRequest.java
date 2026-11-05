package com.example.accounting;

import jakarta.validation.constraints.NotNull;

public record YearCloseRequest(@NotNull Long retainedEarningsAccountId) {
}
