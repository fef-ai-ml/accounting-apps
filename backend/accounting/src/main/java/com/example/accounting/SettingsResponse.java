package com.example.accounting;

import java.time.Instant;

public record SettingsResponse(boolean initialized, boolean requireSeparatePoster, String updatedByName,
		Instant updatedAt) {

	static final SettingsResponse NOT_INITIALIZED = new SettingsResponse(false, true, null, null);

	static SettingsResponse from(Settings settings) {
		return new SettingsResponse(true, settings.isRequireSeparatePoster(), settings.getUpdatedByName(),
				settings.getUpdatedAt());
	}

}
