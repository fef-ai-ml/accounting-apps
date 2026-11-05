package com.example.accounting;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class PeriodService {

	private final FiscalPeriodRepository periods;

	private final JournalEntryRepository entries;

	private final AccountingSettings settings;

	private final AuditTrail audit;

	private final Clock clock;

	PeriodService(FiscalPeriodRepository periods, JournalEntryRepository entries, AccountingSettings settings, AuditTrail audit, Clock clock) {
		this.periods = periods;
		this.entries = entries;
		this.settings = settings;
		this.audit = audit;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public List<PeriodResponse> list(int year) {
		return periods.findByYearOrderByMonth(year).stream().map(PeriodResponse::from).toList();
	}

	public List<PeriodResponse> openYearChecked(int year) {
		settings.require();
		if (year < 2000 || year > 2100) {
			throw AccountingException.badRequest("validation", "Year must be between 2000 and 2100");
		}
		if (periods.existsByYear(year)) {
			throw AccountingException.conflict("periods-exist", "The periods of " + year + " already exist");
		}
		openYear(year);
		return list(year);
	}

	public void openYear(int year) {
		periods.saveAll(IntStream.rangeClosed(1, 12).mapToObj(month -> new FiscalPeriod(year, month)).toList());
	}

	public PeriodResponse close(long id) {
		FiscalPeriod period = periods.lockForClosing(id).orElseThrow(() -> notFound(id));
		if (period.getStatus() == PeriodStatus.CLOSED) {
			return PeriodResponse.from(period);
		}
		long drafts = periods.countDrafts(id);
		if (drafts > 0) {
			throw AccountingException.conflict("period-has-drafts",
					"Post or delete the " + drafts + " draft entries dated in this period first");
		}
		period.close(Actor.current(), clock.instant());
		audit.record(AuditAction.PERIOD_CLOSE, "fiscal_period", id,
				Map.of("year", period.getYear(), "month", period.getMonth()));
		return PeriodResponse.from(period);
	}

	public PeriodResponse reopen(long id, String reason) {
		FiscalPeriod period = periods.lockForClosing(id).orElseThrow(() -> notFound(id));
		if (period.getStatus() == PeriodStatus.OPEN) {
			return PeriodResponse.from(period);
		}
		if (entries.findActiveClose(period.getYear()).isPresent()) {
			throw AccountingException.conflict("year-closed", "Reopen the year " + period.getYear() + " first");
		}
		period.reopen();
		audit.record(AuditAction.PERIOD_REOPEN, "fiscal_period", id,
				Map.of("year", period.getYear(), "month", period.getMonth(), "reason", reason));
		return PeriodResponse.from(period);
	}

	private static AccountingNotFoundException notFound(long id) {
		return new AccountingNotFoundException("Period " + id);
	}

}
