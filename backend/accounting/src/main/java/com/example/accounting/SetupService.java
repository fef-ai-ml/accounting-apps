package com.example.accounting;

import java.time.Clock;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class SetupService {

	private final SettingsRepository settingsRepository;

	private final AccountingSettings settings;

	private final CoaTemplateRepository templates;

	private final AccountRepository accounts;

	private final PeriodService periods;

	private final AuditTrail audit;

	private final Clock clock;

	SetupService(SettingsRepository settingsRepository, AccountingSettings settings, CoaTemplateRepository templates,
			AccountRepository accounts, PeriodService periods, AuditTrail audit, Clock clock) {
		this.settingsRepository = settingsRepository;
		this.settings = settings;
		this.templates = templates;
		this.accounts = accounts;
		this.periods = periods;
		this.audit = audit;
		this.clock = clock;
	}

	public SettingsResponse setup() {
		if (settings.current().isPresent()) {
			throw alreadyInitialized();
		}
		Settings created;
		try {
			created = settingsRepository.saveAndFlush(new Settings(clock.instant()));
		}
		catch (DataIntegrityViolationException ex) {
			// Two admins clicked at once; UNIQUE (tenant_id) let one through.
			throw alreadyInitialized();
		}
		Map<String, Long> idsByCode = new HashMap<>();
		// Sorted by code, a parent always comes before its children.
		for (CoaTemplate template : templates.findAll(Sort.by("code"))) {
			Account account = accounts.save(new Account(template.getCode(), template.getName(), template.getType(),
					idsByCode.get(template.getParentCode()), template.isPostable(), true));
			idsByCode.put(template.getCode(), account.getId());
		}
		int year = LocalDate.now(clock).getYear();
		periods.openYear(year);
		audit.record(AuditAction.SETUP, "settings", created.getId(), Map.of("year", year));
		return SettingsResponse.from(created);
	}

	@Transactional(readOnly = true)
	public SettingsResponse settings() {
		return settings.current().map(SettingsResponse::from).orElse(SettingsResponse.NOT_INITIALIZED);
	}

	public SettingsResponse update(SettingsRequest request) {
		Settings current = settings.require();
		boolean old = current.isRequireSeparatePoster();
		current.requireSeparatePoster(request.requireSeparatePoster(), Actor.current(), clock.instant());
		audit.record(AuditAction.SETTING_CHANGE, "settings", current.getId(),
				Map.of("setting", "require_separate_poster", "old", old, "new", request.requireSeparatePoster()));
		return SettingsResponse.from(current);
	}

	private static AccountingException alreadyInitialized() {
		return AccountingException.conflict("already-initialized", "Accounting is already set up for this company");
	}

}
