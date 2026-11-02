package com.example.accounting;

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

	public EntryResponse post(long id) {
		Actor actor = Actor.current();
		Settings current = settings.require();
		JournalEntry entry = journal.load(id);
		JournalService.requireDraft(entry);
		JournalRules.checkBalanced(entry.sides());
		journal.checkAccountsUsable(entry.getLines().stream().map(JournalLine::getAccountId).toList());
		lockOpenPeriod(entry.getEntryDate());
		if (!JournalRules.mayPost(actor.sub(), entry.getCreatedBySub(), entry.getLastModifiedBySub(),
				current.isRequireSeparatePoster())) {
			throw AccountingException.forbidden("separate-poster-required",
					"You prepared this entry; another user must post it");
		}
		assignNumberAndPost(entry, actor);
		audit.record(AuditAction.POST, "journal_entry", entry.getId(), Map.of("entryNo", entry.getEntryNo()));
		return EntryResponse.from(entry);
	}

	public ReverseResponse reverse(long id, ReverseRequest request) {
		Actor actor = Actor.current();
		settings.require();
		JournalEntry original = journal.load(id);
		if (original.getStatus() == EntryStatus.DRAFT) {
			throw AccountingException.conflict("not-draft", "Only a posted entry can be reversed");
		}
		if (original.getStatus() == EntryStatus.REVERSED) {
			throw AccountingException.conflict("already-reversed", "This entry has already been reversed");
		}
		// The guard on journal_line would otherwise reject these lines with an unexplained 500.
		journal.checkAccountsUsable(original.getLines().stream().map(JournalLine::getAccountId).toList());
		Instant now = clock.instant();
		LocalDate date = request.date() != null ? request.date() : LocalDate.now(clock);
		FiscalPeriod period = lockOpenPeriod(date);

		JournalEntry reversal = new JournalEntry(date, period.getId(), original.getReference(),
				"Reversal of " + original.getEntryNo(), actor, now);
		reversal.reversalOf(original.getId());
		original.getLines().forEach(line -> reversal.addLine(line.getAccountId(), line.side().swapped(), line.getMemo()));
		// Lines cannot be added to a posted entry, so the reversal goes in as a draft first.
		entries.saveAndFlush(reversal);
		assignNumberAndPost(reversal, actor);

		original.markReversed(reversal.getId());
		entries.saveAndFlush(original);
		audit.record(AuditAction.REVERSE, "journal_entry", original.getId(),
				Map.of("entryNo", original.getEntryNo(), "reversalEntryNo", reversal.getEntryNo()));

		Long draftId = Boolean.TRUE.equals(request.copyToDraft()) ? copyToDraft(original, date, period, actor, now) : null;
		return new ReverseResponse(EntryResponse.from(reversal), draftId);
	}

	private Long copyToDraft(JournalEntry original, LocalDate date, FiscalPeriod period, Actor actor, Instant now) {
		JournalEntry copy = new JournalEntry(date, period.getId(), original.getReference(), original.getDescription(),
				actor, now);
		original.getLines().forEach(line -> copy.addLine(line.getAccountId(), line.side(), line.getMemo()));
		return entries.saveAndFlush(copy).getId();
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
