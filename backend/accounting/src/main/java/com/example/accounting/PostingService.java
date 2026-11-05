package com.example.accounting;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class PostingService {

	private final JournalService journal;

	private final JournalEntryRepository entries;

	private final FiscalPeriodRepository periods;

	private final AccountingSettings settings;

	private final EntryNumbers numbers;

	private final AuditTrail audit;

	private final Clock clock;

	PostingService(JournalService journal, JournalEntryRepository entries, FiscalPeriodRepository periods,
			AccountingSettings settings, EntryNumbers numbers, AuditTrail audit, Clock clock) {
		this.journal = journal;
		this.entries = entries;
		this.periods = periods;
		this.settings = settings;
		this.numbers = numbers;
		this.audit = audit;
		this.clock = clock;
	}

	/** {@code version} is the one the approver reviewed; posting an entry edited since is refused. */
	public EntryResponse post(long id, long version) {
		Actor actor = Actor.current();
		Settings current = settings.require();
		JournalEntry entry = journal.loadForChange(id);
		JournalService.requireDraft(entry);
		JournalService.requireVersion(entry, version);
		JournalRules.checkBalanced(entry.sides());
		journal.checkAccountsUsable(entry.getLines().stream().map(JournalLine::getAccountId).toList());
		lockOpenPeriod(entry.getEntryDate());
		if (!JournalRules.mayPost(actor.sub(), entry.preparers(),
				current.isRequireSeparatePoster())) {
			throw AccountingException.forbidden("separate-poster-required",
					"You prepared or edited this entry; another user must post it");
		}
		assignNumberAndPost(entry, actor);
		audit.record(AuditAction.POST, "journal_entry", entry.getId(), Map.of("entryNo", entry.getEntryNo(),
				"totalDebit", entry.sides().stream().map(Side::debit).reduce(BigDecimal.ZERO, BigDecimal::add),
				"version", version));
		return EntryResponse.from(entry);
	}

	public ReverseResponse reverse(long id, ReverseRequest request) {
		Actor actor = Actor.current();
		settings.require();
		JournalEntry original = journal.loadForChange(id);
		if (original.getClosingYear() != null) {
			throw AccountingException.conflict("closing-entry", "A closing entry is undone by reopening its year");
		}
		if (original.getStatus() == EntryStatus.DRAFT) {
			throw AccountingException.conflict("not-draft", "Only a posted entry can be reversed");
		}
		if (original.getStatus() == EntryStatus.REVERSED) {
			throw AccountingException.conflict("already-reversed", "This entry has already been reversed");
		}
		LocalDate date = request.date() != null ? request.date() : LocalDate.now(clock);
		if (date.isBefore(original.getEntryDate())) {
			throw AccountingException.badRequest("validation", "A reversal cannot be dated before the entry it reverses");
		}
		// The guard on journal_line would otherwise reject these lines with an unexplained 500.
		journal.checkAccountsUsable(original.getLines().stream().map(JournalLine::getAccountId).toList());
		FiscalPeriod period = lockOpenPeriod(date);

		JournalEntry reversal = postReversal(original, date, period);
		audit.record(AuditAction.REVERSE, "journal_entry", original.getId(),
				Map.of("entryNo", original.getEntryNo(), "reversalEntryNo", reversal.getEntryNo()));

		Long draftId = Boolean.TRUE.equals(request.copyToDraft()) ? copyToDraft(original, date, period, actor, clock.instant()) : null;
		return new ReverseResponse(EntryResponse.from(reversal), draftId);
	}

	/** Posts the mirror entry and marks the original. The caller has checked the accounts and holds the period, which is open. */
	JournalEntry postReversal(JournalEntry original, LocalDate date, FiscalPeriod period) {
		JournalEntry reversal = new JournalEntry(date, period.getId(), original.getReference(),
				"Reversal of " + original.getEntryNo(), Actor.current(), clock.instant());
		reversal.reversalOf(original.getId());
		reversal.closingYear(original.getClosingYear());
		original.getLines().forEach(line -> reversal.addLine(line.getAccountId(), line.side().swapped(), line.getMemo()));
		postDirectly(reversal);

		original.markReversed(reversal.getId());
		entries.saveAndFlush(original);
		return reversal;
	}

	private Long copyToDraft(JournalEntry original, LocalDate date, FiscalPeriod period, Actor actor, Instant now) {
		JournalEntry copy = new JournalEntry(date, period.getId(), original.getReference(), original.getDescription(),
				actor, now);
		original.getLines().forEach(line -> copy.addLine(line.getAccountId(), line.side(), line.getMemo()));
		return entries.saveAndFlush(copy).getId();
	}

	/** Numbers and posts an entry the system built; no maker/checker. The caller holds its period, which must be open. */
	void postDirectly(JournalEntry entry) {
		Actor actor = Actor.current();
		// Lines cannot be added to a posted entry, so it goes in as a draft first.
		entries.saveAndFlush(entry);
		assignNumberAndPost(entry, actor);
	}

	/** Holds the period FOR SHARE until commit, so a close either waits for this post or this post sees it closed. */
	private FiscalPeriod lockOpenPeriod(LocalDate date) {
		FiscalPeriod period = periods.lockForPosting(date.getYear(), date.getMonthValue())
				.orElseThrow(() -> AccountingException.unprocessable("no-period", "No fiscal period exists for " + date));
		if (period.getStatus() != PeriodStatus.OPEN) {
			throw AccountingException.unprocessable("period-closed", "The period of " + date + " is closed");
		}
		return period;
	}

	private void assignNumberAndPost(JournalEntry entry, Actor actor) {
		entry.markPosted(numbers.next(entry.getEntryDate().getYear()), actor, clock.instant());
		entries.saveAndFlush(entry);
	}

}
