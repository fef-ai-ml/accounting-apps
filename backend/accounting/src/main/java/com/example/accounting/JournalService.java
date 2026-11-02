package com.example.accounting;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class JournalService {

	static final int MAX_PAGE_SIZE = 200;

	private final JournalEntryRepository entries;

	private final AccountRepository accounts;

	private final FiscalPeriodRepository periods;

	private final AccountingSettings settings;

	private final Clock clock;

	JournalService(JournalEntryRepository entries, AccountRepository accounts, FiscalPeriodRepository periods,
			AccountingSettings settings, Clock clock) {
		this.entries = entries;
		this.accounts = accounts;
		this.periods = periods;
		this.settings = settings;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public EntryPage list(LocalDate from, LocalDate to, EntryStatus status, Long accountId, String q, int page, int size) {
		if (size < 1 || size > MAX_PAGE_SIZE || page < 0) {
			throw AccountingException.badRequest("validation",
					"page must not be negative and size must be between 1 and " + MAX_PAGE_SIZE);
		}
		if ((long) page * size > Integer.MAX_VALUE) {
			throw AccountingException.badRequest("validation", "page is too large");
		}
		if (from != null && to != null && from.isAfter(to)) {
			throw AccountingException.badRequest("validation", "from must not be after to");
		}
		Page<JournalEntry> result = entries.findAll(filter(from, to, status, accountId, q),
				PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "entryDate", "id")));
		return new EntryPage(result.stream().map(EntrySummary::from).toList(), result.getTotalElements(), page, size);
	}

	@Transactional(readOnly = true)
	public EntryResponse get(long id) {
		return EntryResponse.from(load(id));
	}

	public EntryResponse create(EntryRequest request) {
		settings.require();
		List<ValidLine> lines = validated(request.lines());
		JournalEntry entry = new JournalEntry(request.date(), periodOf(request.date()), request.reference(),
				request.description(), Actor.current(), clock.instant());
		lines.forEach(line -> entry.addLine(line.accountId(), line.side(), line.memo()));
		return EntryResponse.from(entries.saveAndFlush(entry));
	}

	public EntryResponse update(long id, EntryRequest request) {
		JournalEntry entry = load(id);
		requireDraft(entry);
		if (request.version() == null) {
			throw AccountingException.badRequest("validation", "version is required when updating an entry");
		}
		requireVersion(entry, request.version());
		List<ValidLine> lines = validated(request.lines());
		entry.edit(request.date(), periodOf(request.date()), request.reference(), request.description(),
				Actor.current(), clock.instant());
		// Hibernate inserts before it deletes, which would trip UNIQUE (tenant_id, entry_id, line_no).
		entry.getLines().clear();
		entries.flush();
		lines.forEach(line -> entry.addLine(line.accountId(), line.side(), line.memo()));
		return EntryResponse.from(entries.saveAndFlush(entry));
	}

	public void delete(long id, long version) {
		JournalEntry entry = load(id);
		requireDraft(entry);
		requireVersion(entry, version);
		entries.delete(entry);
	}

	public JournalEntry load(long id) {
		return entries.findById(id).orElseThrow(() -> new AccountingNotFoundException("Journal entry " + id));
	}

	static void requireDraft(JournalEntry entry) {
		if (entry.getStatus() != EntryStatus.DRAFT) {
			throw AccountingException.conflict("not-draft", "Only a draft entry can be changed");
		}
	}

	static void requireVersion(JournalEntry entry, long version) {
		if (entry.getVersion() == null || entry.getVersion() != version) {
			throw AccountingException.conflict("version-conflict", "Someone else changed this entry");
		}
	}

	/** An unknown id and another tenant's id are refused the same way as an unusable account. */
	public void checkAccountsUsable(Collection<Long> accountIds) {
		Set<Long> ids = Set.copyOf(accountIds);
		Map<Long, Account> found = accounts.findAllById(ids).stream()
				.collect(Collectors.toMap(Account::getId, Function.identity()));
		for (Long id : ids) {
			Account account = found.get(id);
			if (account == null || !account.isPostable() || !account.isActive()) {
				throw AccountingException.unprocessable("account-not-postable",
						"Account " + (account != null ? account.getCode() : id) + " cannot receive journal lines");
			}
		}
	}

	private List<ValidLine> validated(List<LineRequest> requested) {
		if (requested.size() > JournalRules.MAX_LINES) {
			throw AccountingException.badRequest("too-many-lines",
					"An entry can have at most " + JournalRules.MAX_LINES + " lines");
		}
		List<ValidLine> lines = new ArrayList<>();
		for (LineRequest line : requested) {
			Side side = new Side(JournalRules.amount(line.debit()), JournalRules.amount(line.credit()));
			JournalRules.checkLine(side);
			lines.add(new ValidLine(line.accountId(), side, line.memo()));
		}
		checkAccountsUsable(lines.stream().map(ValidLine::accountId).toList());
		return lines;
	}

	private Long periodOf(LocalDate date) {
		return periods.findByYearAndMonth(date.getYear(), date.getMonthValue())
				.orElseThrow(() -> AccountingException.unprocessable("no-period", "No fiscal period exists for " + date))
				.getId();
	}

	private static Specification<JournalEntry> filter(LocalDate from, LocalDate to, EntryStatus status, Long accountId,
			String q) {
		return (root, query, cb) -> {
			List<Predicate> predicates = new ArrayList<>();
			if (from != null) {
				predicates.add(cb.greaterThanOrEqualTo(root.<LocalDate>get("entryDate"), from));
			}
			if (to != null) {
				predicates.add(cb.lessThanOrEqualTo(root.<LocalDate>get("entryDate"), to));
			}
			if (status != null) {
				predicates.add(cb.equal(root.get("status"), status));
			}
			if (accountId != null) {
				Subquery<Long> lines = query.subquery(Long.class);
				Root<JournalLine> line = lines.from(JournalLine.class);
				lines.select(line.<Long>get("id"))
						.where(cb.equal(line.get("entry"), root), cb.equal(line.get("accountId"), accountId));
				predicates.add(cb.exists(lines));
			}
			if (q != null && !q.isBlank()) {
				// The search text is literal: % and _ typed by a user are not wildcards.
				String pattern = "%" + q.strip().toLowerCase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
				predicates.add(cb.or(cb.like(cb.lower(root.<String>get("description")), pattern, '\\'),
						cb.like(cb.lower(root.<String>get("reference")), pattern, '\\'),
						cb.like(cb.lower(root.<String>get("entryNo")), pattern, '\\')));
			}
			return cb.and(predicates.toArray(Predicate[]::new));
		};
	}

	private record ValidLine(Long accountId, Side side, String memo) {
	}

}
