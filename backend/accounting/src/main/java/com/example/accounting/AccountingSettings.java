package com.example.accounting;

import java.util.Optional;

import org.springframework.stereotype.Component;

@Component
class AccountingSettings {

	private final SettingsRepository repository;

	AccountingSettings(SettingsRepository repository) {
		this.repository = repository;
	}

	Optional<Settings> current() {
		return repository.findFirstByOrderByIdAsc();
	}

	Settings require() {
		return current().orElseThrow(AccountingSettings::notInitialized);
	}

	/** Closing and reopening a year start here, so they run one at a time in a tenant. */
	Settings lock() {
		return repository.lockCurrent().orElseThrow(AccountingSettings::notInitialized);
	}

	private static AccountingException notInitialized() {
		return AccountingException.unprocessable("not-initialized", "Accounting has not been set up for this company yet");
	}

}
