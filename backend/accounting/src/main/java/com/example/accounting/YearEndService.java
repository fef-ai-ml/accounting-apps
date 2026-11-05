package com.example.accounting;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Closes a year into retained earnings. Every refusal is checked here, before the database guards would see it. */
@Service
@Transactional
public class YearEndService {

	private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

	private final JournalEntryRepository entries;

	private final AccountRepository accounts;

	private final FiscalPeriodRepository periods;

	private final AccountingSettings settings;

	private final ReportService reports;

	private final JournalService journal;

	private final PostingService posting;

	private final AuditTrail audit;

	private final Clock clock;

	YearEndService(JournalEntryRepository entries, AccountRepository accounts, FiscalPeriodRepository periods,
			AccountingSettings settings, ReportService reports, JournalService journal, PostingService posting,
			AuditTrail audit, Clock clock) {
		this.entries = entries;
		this.accounts = accounts;
		this.periods = periods;
		this.settings = settings;
		this.reports = reports;
		this.journal = journal;
		this.posting = posting;
		this.audit = audit;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public YearStatus status(int year) {
		return entries.findActiveClose(year)
				.map(close -> new YearStatus(year, true, close.getId(), close.getEntryNo()))
				.orElse(new YearStatus(year, false, null, null));
	}

	public YearCloseResponse close(int year, long retainedEarningsAccountId) {
		settings.lock();
		List<FiscalPeriod> months = periods.lockYear(year);
		if (months.size() != 12) {
			throw AccountingException.unprocessable("no-period", "The 12 periods of " + year + " do not exist");
		}
		if (entries.findActiveClose(year).isPresent()) {
			throw AccountingException.conflict("year-closed", year + " is already closed");
		}
		LocalDate lastDay = LocalDate.of(year, 12, 31);
		LocalDate firstDay = LocalDate.of(year, 1, 1);
		long drafts = entries.countDraftsBetween(firstDay, lastDay);
		if (drafts > 0) {
			throw AccountingException.conflict("year-has-drafts",
					"Post or delete the " + drafts + " draft entries dated in " + year + " first");
		}
		List<Account> incomeAccounts = accounts.findAll().stream()
				.filter(account -> account.getType() == AccountType.REVENUE || account.getType() == AccountType.EXPENSE)
				.sorted(Comparator.comparing(Account::getCode))
				.toList();
		Map<Long, BigDecimal> before = reports.debitMinusCredit(firstDay.minusDays(1));
		incomeAccounts.stream().filter(account -> before.getOrDefault(account.getId(), ZERO).signum() != 0).findFirst()
				.ifPresent(account -> {
					throw AccountingException.conflict("earlier-year-open",
							"Close the earlier year first: account " + account.getCode() + " has a balance dated before " + firstDay);
				});
		Map<Long, BigDecimal> through = reports.debitMinusCredit(lastDay);
		List<Account> toClose = incomeAccounts.stream()
				.filter(account -> through.getOrDefault(account.getId(), ZERO).signum() != 0)
				.toList();
		if (toClose.isEmpty()) {
			throw AccountingException.conflict("nothing-to-close", "No revenue or expense account has a balance in " + year);
		}
		journal.checkAccountsUsable(toClose.stream().map(Account::getId).toList());
		Account retained = accounts.findById(retainedEarningsAccountId)
				.filter(account -> account.getType() == AccountType.EQUITY && account.isPostable() && account.isActive())
				.orElseThrow(() -> AccountingException.unprocessable("bad-retained-earnings-account",
						"Retained earnings must be a postable, active equity account"));

		Actor actor = Actor.current();
		Instant now = clock.instant();
		FiscalPeriod december = months.get(11);
		if (december.getStatus() == PeriodStatus.CLOSED) {
			// The posting guard needs an open period; this flush puts OPEN in the database before the post.
			december.reopen();
			periods.saveAndFlush(december);
		}
		JournalEntry closing = new JournalEntry(lastDay, december.getId(), null, "Closing entry for " + year, actor, now);
		closing.closingYear(year);
		BigDecimal net = ZERO;
		for (Account account : toClose) {
			BigDecimal balance = through.get(account.getId());
			closing.addLine(account.getId(), towardZero(balance), null);
			net = net.subtract(balance);
		}
		if (net.signum() != 0) {
			// A profit is a credit and a loss a debit, the same sides as a positive and a negative balance above.
			closing.addLine(retained.getId(), towardZero(net), null);
		}
		posting.postDirectly(closing);
		months.stream().filter(month -> month.getStatus() == PeriodStatus.OPEN).forEach(month -> month.close(actor, now));
		audit.record(AuditAction.YEAR_CLOSE, "journal_entry", closing.getId(), Map.of("year", year, "entryNo", closing.getEntryNo(),
				"netProfit", net, "retainedEarningsAccount", retained.getCode()));
		return new YearCloseResponse(year, closing.getId(), closing.getEntryNo(), net);
	}

	/** A credit for a positive amount and a debit for a negative one: the side that takes a debit balance to zero. */
	private static Side towardZero(BigDecimal amount) {
		return amount.signum() > 0 ? new Side(ZERO, amount) : new Side(amount.negate(), ZERO);
	}

}
