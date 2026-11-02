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
		return current().orElseThrow(() -> AccountingException.unprocessable("not-initialized",
				"Accounting has not been set up for this company yet"));
	}

}
