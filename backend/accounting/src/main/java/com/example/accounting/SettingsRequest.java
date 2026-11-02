package com.example.accounting;

import jakarta.validation.constraints.NotNull;

public record SettingsRequest(@NotNull Boolean requireSeparatePoster) {
}
